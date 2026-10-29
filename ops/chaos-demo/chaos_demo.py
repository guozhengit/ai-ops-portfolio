"""chaos-demo —— 自愈型运维 Agent 的故障注入靶场服务（W2 D8）。

设计原则（见 prototypes/self-healing-agent/DESIGN.md）：
- 故障尽量「真」：真内存分配（容器 OOMKilled）、真文件写入、真信号量竞争
- 基础设施缺失处诚实模拟：慢 SQL = 真实 sleep + 指标；磁盘 = 有上限的专属目录，
  指标按「模拟配额」计算，README 里写明，不冒充真磁盘
- 全部场景可开可关、幂等、一键清理；只碰自己的资源，严禁波及业务栈

运行：python chaos_demo.py  （端口 9400）
控制：POST /fault/<scene>/on|off   GET /status   GET /metrics
场景：memory-leak / slow-sql / disk-full / pool-exhaustion / nacos-flap
"""

import os
import random
import threading
import time
from http import HTTPStatus

from flask import Flask, jsonify, request, Response
from prometheus_client import (
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

APP_PORT = int(os.getenv("CHAOS_PORT", "9400"))
DATA_DIR = os.getenv("CHAOS_DATA_DIR", "/data/chaos-logs")
DISK_QUOTA_BYTES = int(os.getenv("CHAOS_DISK_QUOTA", str(500 * 1024 * 1024)))  # 模拟配额 500MB
POOL_SIZE = int(os.getenv("CHAOS_POOL_SIZE", "10"))

app = Flask(__name__)
registry = CollectorRegistry()

# ---- 指标 ----
MEMORY_RSS_BYTES = Gauge("chaos_memory_rss_bytes", "进程 RSS 字节数", registry=registry)
MEMORY_ALLOCATED = Gauge("chaos_memory_leak_bytes", "泄漏场景累计分配字节数", registry=registry)
DB_QUERY_SECONDS = Histogram(
    "chaos_db_query_seconds", "模拟 SQL 查询耗时",
    buckets=(0.05, 0.1, 0.5, 1, 2, 5, 10), registry=registry,
)
DISK_USAGE_RATIO = Gauge("chaos_disk_usage_ratio", "日志目录用量 / 模拟配额", registry=registry)
DISK_USAGE_BYTES = Gauge("chaos_disk_usage_bytes", "日志目录已写字节数", registry=registry)
POOL_IN_USE = Gauge("chaos_pool_in_use", "模拟连接池占用数", registry=registry)
NACOS_REGISTERED = Gauge("chaos_nacos_registered", "模拟 Nacos 注册状态 1/0", registry=registry)
FAULT_ACTIVE = Gauge(
    "chaos_fault_active", "当前激活的故障场景", ["scene"], registry=registry,
)
FAULT_TOGGLES = Counter("chaos_fault_toggles_total", "故障开关次数", ["scene", "action"], registry=registry)


def read_rss_bytes() -> int:
    """Linux 容器里读 /proc/self/status；拿不到就退回泄漏计数。"""
    try:
        with open("/proc/self/status", encoding="utf-8") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return int(MEMORY_ALLOCATED._value.get())  # noqa: SLF001


# ---- 场景 1：内存泄漏（真分配，容器 mem_limit 到顶即 OOMKilled） ----
leak_stop = threading.Event()
leak_chunks: list[bytes] = []


def leak_loop():
    while not leak_stop.is_set():
        chunk = b"x" * (30 * 1024 * 1024)  # 每 10s +30MB
        leak_chunks.append(chunk)
        MEMORY_ALLOCATED.set(sum(len(c) for c in leak_chunks))
        MEMORY_RSS_BYTES.set(read_rss_bytes())
        time.sleep(10)


# ---- 场景 2：慢 SQL（真实 sleep，指标进 histogram） ----
sql_stop = threading.Event()


def sql_loop():
    """后台流量：常驻每秒 2 次查询；故障开 = 2~8s 慢查询，关 = 10~50ms。"""
    while not sql_stop.is_set():
        slow = FAULT_ACTIVE.labels(scene="slow-sql")._value.get() >= 1  # noqa: SLF001
        delay = random.uniform(2.0, 8.0) if slow else random.uniform(0.01, 0.05)
        start = time.perf_counter()
        time.sleep(delay)
        DB_QUERY_SECONDS.observe(time.perf_counter() - start)


# ---- 场景 3：磁盘打满（真写文件，专属目录 + 模拟配额） ----
disk_stop = threading.Event()


def disk_loop():
    os.makedirs(DATA_DIR, exist_ok=True)
    payload = b"chaos-demo disk-full filler line with timestamp %d\n" % time.time()
    payload = payload * 1024 * 10  # 每轮 ~450KB
    path = os.path.join(DATA_DIR, "filler.log")
    with open(path, "ab") as f:
        while not disk_stop.is_set():
            f.write(payload)
            f.flush()
            used = os.path.getsize(path)
            DISK_USAGE_BYTES.set(used)
            DISK_USAGE_RATIO.set(min(1.0, used / DISK_QUOTA_BYTES))
            time.sleep(2)


# ---- 场景 4：连接池打满（真信号量竞争） ----
pool = threading.BoundedSemaphore(POOL_SIZE)
pool_stop = threading.Event()
pool_holders: list[threading.Thread] = []


def pool_holder():
    while not pool_stop.is_set():
        acquired = pool.acquire(timeout=1)
        if acquired:
            try:
                POOL_IN_USE.inc()
                time.sleep(30)  # 占住连接 30s
            finally:
                POOL_IN_USE.dec()
                pool.release()
        else:
            time.sleep(0.5)


# ---- 场景 5：Nacos 注册闪断（心跳线程停摆 + gauge 归零） ----
nacos_stop = threading.Event()


def nacos_loop():
    while not nacos_stop.is_set():
        active = FAULT_ACTIVE.labels(scene="nacos-flap")._value.get() >= 1  # noqa: SLF001
        NACOS_REGISTERED.set(0 if active else 1)
        time.sleep(1)


SCENES = {
    "memory-leak": (leak_stop, leak_loop),
    # slow-sql / nacos-flap 的循环是常驻线程（服务生命周期内不退出），
    # 开关只翻转 FAULT_ACTIVE；stop 事件为 None，防止 off 误杀常驻线程
    "slow-sql": (None, None),
    "disk-full": (disk_stop, disk_loop),
    "pool-exhaustion": (pool_stop, None),  # holder 线程按需起
    "nacos-flap": (None, None),
}

WORKERS: dict[str, list[threading.Thread]] = {}


def start_workers(scene: str):
    """起场景 worker（幂等：已活着就跳过）。"""
    stop_event, loop = SCENES[scene]
    if scene == "pool-exhaustion":
        if scene not in WORKERS or not any(t.is_alive() for t in WORKERS[scene]):
            pool_stop.clear()
            WORKERS[scene] = []
            for _ in range(POOL_SIZE):  # 10 个 holder 把池占满
                t = threading.Thread(target=pool_holder, daemon=True)
                t.start()
                WORKERS[scene].append(t)
        return
    if loop is None:
        return
    if scene in WORKERS and any(t.is_alive() for t in WORKERS[scene]):
        return
    stop_event.clear()
    t = threading.Thread(target=loop, daemon=True)
    t.start()
    WORKERS[scene] = [t]


def stop_workers(scene: str):
    stop_event, _ = SCENES[scene]
    if stop_event is None:  # 常驻循环场景：开关只翻转 FAULT_ACTIVE，不动线程
        return
    stop_event.set()
    if scene == "memory-leak":
        leak_chunks.clear()
        MEMORY_ALLOCATED.set(0)
    if scene == "disk-full":
        try:
            os.remove(os.path.join(DATA_DIR, "filler.log"))
        except OSError:
            pass
        DISK_USAGE_BYTES.set(0)
        DISK_USAGE_RATIO.set(0)


@app.get("/metrics")
def metrics():
    MEMORY_RSS_BYTES.set(read_rss_bytes())
    return Response(generate_latest(registry), mimetype="text/plain")


@app.get("/api/work")
def api_work():
    """业务路径：正常走池 + 查询；池被打满时这里会排队/超慢，便于对照。"""
    acquired = pool.acquire(timeout=5)
    if not acquired:
        return jsonify(error="pool exhausted, acquire timeout"), HTTPStatus.SERVICE_UNAVAILABLE
    try:
        slow = FAULT_ACTIVE.labels(scene="slow-sql")._value.get() >= 1  # noqa: SLF001
        time.sleep(random.uniform(2.0, 8.0) if slow else random.uniform(0.01, 0.05))
        return jsonify(ok=True)
    finally:
        pool.release()


@app.post("/fault/<scene>/<action>")
def toggle_fault(scene: str, action: str):
    if scene not in SCENES:
        return jsonify(error=f"unknown scene: {scene}"), HTTPStatus.NOT_FOUND
    if action not in ("on", "off"):
        return jsonify(error="action must be on|off"), HTTPStatus.BAD_REQUEST
    want = 1 if action == "on" else 0
    current = FAULT_ACTIVE.labels(scene=scene)._value.get() >= 1  # noqa: SLF001
    if (action == "on") != current:  # 幂等：状态不变就不重复动作
        FAULT_ACTIVE.labels(scene=scene).set(want)
        FAULT_TOGGLES.labels(scene=scene, action=action).inc()
        if action == "on":
            start_workers(scene)
        else:
            stop_workers(scene)
    active = [s for s, (ev, _) in SCENES.items() if FAULT_ACTIVE.labels(scene=s)._value.get() >= 1]  # noqa: SLF001
    return jsonify(scene=scene, action=action, active=active)


@app.get("/status")
def status():
    active = [s for s, (ev, _) in SCENES.items() if FAULT_ACTIVE.labels(scene=s)._value.get() >= 1]  # noqa: SLF001
    return jsonify(
        active=active,
        rss_bytes=read_rss_bytes(),
        disk_used_bytes=int(DISK_USAGE_BYTES._value.get()),  # noqa: SLF001
        pool_in_use=int(POOL_IN_USE._value.get()),  # noqa: SLF001
        nacos_registered=int(NACOS_REGISTERED._value.get()),  # noqa: SLF001
        scenes=list(SCENES),
    )


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    threading.Thread(target=sql_loop, daemon=True).start()
    threading.Thread(target=nacos_loop, daemon=True).start()
    NACOS_REGISTERED.set(1)
    app.run(host="0.0.0.0", port=APP_PORT, threaded=True)
