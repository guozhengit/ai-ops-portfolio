# chaos-demo — 故障注入靶场（原型 1 的故障源）

> **一句话**：一个可以「按场景弄坏」的 demo 服务，给自愈型运维 Agent 提供真实故障：
> 真 OOM、真慢查询、真文件膨胀、真连接池竞争；磁盘与 Nacos 为诚实模拟（见下）。
> 场景定义与验收标准见 [prototypes/self-healing-agent/DESIGN.md](../../prototypes/self-healing-agent/DESIGN.md)。

## 5 个场景

| 场景 | 实现 | 真实度 |
| --- | --- | --- |
| memory-leak | 每 10s 真分配 30MB，容器 256m 限额到顶 → 真 OOMKilled + 自动重启 | 完全真实 |
| slow-sql | 真实 sleep 2-8s，histogram 指标出 p95 | 行为真实（无真 DB） |
| disk-full | 真写专属卷，按 500MB **模拟配额**算用量 | 部分模拟（不冒充真磁盘） |
| pool-exhaustion | 真信号量 10 连接被 holder 占满，`/api/work` 排队超时 | 完全真实 |
| nacos-flap | 心跳线程停摆 + `chaos_nacos_registered` 归零 | 模拟（未接真 Nacos） |

## 快速开始

```bash
cd ops/chaos-demo
docker compose up -d --build          # 160 上部署
curl http://<host>:9400/status        # 查看当前状态

# 注入 / 解除（幂等，重复下发无副作用）
curl -X POST http://<host>:9400/fault/memory-leak/on
curl -X POST http://<host>:9400/fault/memory-leak/off

curl -X POST http://<host>:9400/fault/slow-sql/on
curl -X POST http://<host>:9400/fault/pool-exhaustion/on
curl -X POST http://<host>:9400/fault/disk-full/on
curl -X POST http://<host>:9400/fault/nacos-flap/on
```

本地裸跑（无 Docker）：

```bash
pip install -r requirements.txt
python chaos_demo.py                  # 端口 9400
```

## Prometheus 接入

1. 160 的 prometheus.yml 加 scrape job：`job_name: chaos-demo`，target `<host>:9400`
2. 告警规则样例见 [alerts-chaos-demo.yml](alerts-chaos-demo.yml)（6 条：P0 服务不可达 +
   P1 内存/慢SQL/池满/Nacos + P2 磁盘）
3. 采纳后 `curl -X POST http://192.168.1.160:9091/-/reload`（热变更，不重启）

## 安全边界（铁律）

- compose 项目名固定 `chaos-demo`，**不加入任何业务网络**，与 hotel-booking /
  silver-guardian 零共享
- 磁盘膨胀只发生在专属卷 `chaos-logs`，配额 500MB，down -v 一键回收
- 内存上限 256m 由 cgroup 强制，OOM 只杀自己
- 一键清理：`docker compose down -v --rmi local`

## 踩坑记录

- **常驻线程被 off 误杀**（2026-10-29 冒烟发现）：初版把 slow-sql/nacos-flap 的
  stop 事件同时用作常驻循环的退出条件，导致「关故障」把查询流量/心跳线程一起杀掉，
  指标永久停在故障态。修复：常驻循环只在进程退出时结束，场景开关只翻转
  FAULT_ACTIVE gauge；off 时显式复位磁盘 gauge。—— 这正是「外部验证不许自评」
  的意义：仅看代码没发现，冒烟 on→off 才暴露。
- 本机（沙箱环境）`os.remove` 被安全层拦截转垃圾桶，filler.log 删除会打告警日志但
  不影响流程；容器内为正常 unlink，无此问题。
