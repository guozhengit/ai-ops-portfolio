"""chaos-demo 控制面板 UI（内嵌单页，由 Flask 同源 serve，无外部依赖）。

GET /  返回本面板。功能：
- 5 个故障场景开关卡片（点击切换，幂等）
- 实时状态轮询（/status，2s 间隔）：RSS / 磁盘 / 连接池 / Nacos
- 业务路径探活按钮（GET /api/work，显示 HTTP 码与耗时）
- 一键全部解除
"""

UI_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>chaos-demo 故障注入靶场</title>
<style>
  :root {
    --bg: #141416; --panel: #1e1e21; --card: #232327; --line: #333338;
    --fg: #d4d4d8; --dim: #8b8b93;
    --ok: #34d399; --bad: #f87171; --warn: #fbbf24; --accent: #60a5fa;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: var(--bg); color: var(--fg); font-family: "Segoe UI", "Microsoft YaHei", sans-serif; min-height: 100vh; padding: 24px; }
  header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
  header h1 { font-size: 20px; font-weight: 600; }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--ok); }
  .dot.off { background: var(--bad); }
  header .meta { color: var(--dim); font-size: 12px; margin-left: auto; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; margin-bottom: 20px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 10px; padding: 16px; transition: border-color .2s; }
  .card.active { border-color: var(--bad); box-shadow: 0 0 0 1px rgba(248,113,113,.25); }
  .card h3 { font-size: 15px; margin-bottom: 4px; display: flex; align-items: center; justify-content: space-between; }
  .badge { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: #2a2a2f; color: var(--dim); }
  .card.active .badge { background: rgba(248,113,113,.15); color: var(--bad); }
  .card p { font-size: 12px; color: var(--dim); line-height: 1.6; margin-bottom: 12px; }
  button { cursor: pointer; border: none; border-radius: 6px; padding: 8px 16px; font-size: 13px; font-weight: 500; }
  .btn-on { background: var(--bad); color: #fff; }
  .btn-off { background: #2f2f35; color: var(--fg); border: 1px solid var(--line); }
  .btn-on:hover { filter: brightness(1.15); } .btn-off:hover { background: #3a3a41; }
  .btn-ghost { background: transparent; color: var(--dim); border: 1px dashed var(--line); }
  .btn-ghost:hover { color: var(--fg); border-color: var(--dim); }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .metrics { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
  .metrics h2 { font-size: 14px; color: var(--dim); font-weight: 500; margin-bottom: 12px; }
  .m-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
  .m-item { background: var(--card); border-radius: 8px; padding: 12px; }
  .m-item .label { font-size: 11px; color: var(--dim); margin-bottom: 4px; }
  .m-item .value { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
  .m-item .value.ok { color: var(--ok); } .m-item .value.bad { color: var(--bad); } .m-item .value.warn { color: var(--warn); }
  .probe { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--line); }
  #probe-result { font-size: 13px; color: var(--dim); min-height: 20px; margin-top: 8px; }
  footer { margin-top: 16px; font-size: 11px; color: var(--dim); line-height: 1.7; }
  .toast { position: fixed; bottom: 24px; right: 24px; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 10px 16px; font-size: 13px; opacity: 0; transition: opacity .3s; pointer-events: none; }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<header>
  <div class="dot" id="conn-dot"></div>
  <h1>chaos-demo 故障注入靶场</h1>
  <span class="meta" id="refresh-time">连接中…</span>
  <button class="btn-ghost" onclick="releaseAll()">一键全部解除</button>
</header>

<div class="grid" id="scene-grid"></div>

<div class="metrics">
  <h2>实时指标（每 2s 轮询 /status）</h2>
  <div class="m-grid">
    <div class="m-item"><div class="label">进程 RSS</div><div class="value" id="m-rss">0 MB</div></div>
    <div class="m-item"><div class="label">泄漏累计分配</div><div class="value" id="m-leak">0 MB</div></div>
    <div class="m-item"><div class="label">磁盘用量 / 500MB 配额</div><div class="value" id="m-disk">0%</div></div>
    <div class="m-item"><div class="label">连接池占用 / 10</div><div class="value" id="m-pool">0</div></div>
    <div class="m-item"><div class="label">Nacos 注册状态（模拟）</div><div class="value ok" id="m-nacos">已注册</div></div>
  </div>
  <div class="probe">
    <div class="row">
      <button class="btn-off" onclick="probeApi()">探活业务路径 GET /api/work</button>
      <span style="font-size:12px;color:var(--dim)">池被打满时会 503 / 超时——这就是 Agent 要诊断的「业务症状」</span>
    </div>
    <div id="probe-result"></div>
  </div>
</div>

<footer>
  场景定义与验收标准见 prototypes/self-healing-agent/DESIGN.md · 隔离纪律：专属卷 / 256m 内存限额（容器）/ 不接业务网络<br>
  本机裸跑无 cgroup 限额：memory-leak 场景验证完务必解除
</footer>
<div class="toast" id="toast"></div>

<script>
const SCENES = [
  { id: "memory-leak",    name: "内存泄漏",     desc: "每 10s 真分配 30MB，逼近容器 256m 限额触发真 OOMKilled（本机无限额，只看增长）" },
  { id: "slow-sql",       name: "慢 SQL",       desc: "查询延迟升至 2~8s，histogram 指标出 p95 尖刺" },
  { id: "disk-full",      name: "磁盘打满",     desc: "真实写入专属卷，按 500MB 模拟配额计算用量，超 85% 触发 P2 告警" },
  { id: "pool-exhaustion",name: "连接池打满",   desc: "10 个连接被 holder 占满 30s/轮，业务路径 /api/work 排队到 503" },
  { id: "nacos-flap",     name: "Nacos 闪断",   desc: "心跳线程停摆，注册状态 gauge 归零（模拟）" },
];
let active = [];
let busy = {};

function render() {
  const grid = document.getElementById("scene-grid");
  grid.innerHTML = "";
  for (const s of SCENES) {
    const on = active.includes(s.id);
    const card = document.createElement("div");
    card.className = "card" + (on ? " active" : "");
    card.innerHTML =
      '<h3><span>' + s.name + '</span><span class="badge">' + (on ? "故障注入中" : "正常") + '</span></h3>' +
      '<p>' + s.desc + '</p>' +
      '<button class="' + (on ? "btn-off" : "btn-on") + '"' + (busy[s.id] ? " disabled" : "") + '>' +
      (busy[s.id] ? "处理中…" : on ? "解除故障" : "注入故障") + '</button>';
    card.querySelector("button").onclick = () => toggle(s.id, on ? "off" : "on");
    grid.appendChild(card);
  }
}

async function refresh() {
  try {
    const r = await fetch("/status");
    const d = await r.json();
    active = d.active || [];
    document.getElementById("conn-dot").classList.remove("off");
    document.getElementById("refresh-time").textContent = "已连接 · " + new Date().toLocaleTimeString();
    setM("m-rss", fmtMB(d.rss_bytes), d.rss_bytes > 150*1024*1024 ? "bad" : (d.rss_bytes > 50*1024*1024 ? "warn" : ""));
    setM("m-leak", fmtMB(d.leak_bytes || 0), active.includes("memory-leak") ? "bad" : "");
    const ratio = d.disk_used_bytes / (500*1024*1024);
    setM("m-disk", (ratio*100).toFixed(1) + "%", ratio > 0.85 ? "bad" : ratio > 0.5 ? "warn" : "");
    setM("m-pool", d.pool_in_use + " / 10", d.pool_in_use >= 10 ? "bad" : d.pool_in_use > 0 ? "warn" : "ok");
    const nacosEl = document.getElementById("m-nacos");
    nacosEl.textContent = d.nacos_registered ? "已注册" : "掉线";
    nacosEl.className = "value " + (d.nacos_registered ? "ok" : "bad");
    render();
  } catch (e) {
    document.getElementById("conn-dot").classList.add("off");
    document.getElementById("refresh-time").textContent = "连接失败：服务未响应";
  }
}

function setM(id, text, cls) {
  const el = document.getElementById(id);
  el.textContent = text;
  el.className = "value " + (cls || "");
}

function fmtMB(b) { return b ? (b/1048576).toFixed(1) + " MB" : "0 MB"; }

async function toggle(scene, action) {
  busy[scene] = true; render();
  try {
    const r = await fetch("/fault/" + scene + "/" + action, { method: "POST" });
    const d = await r.json();
    toast((action === "on" ? "已注入 " : "已解除 ") + scene);
  } catch (e) { toast("操作失败：" + e); }
  busy[scene] = false;
  await refresh();
}

async function releaseAll() {
  for (const s of active.slice()) { await toggle(s, "off"); }
  toast("已全部解除");
}

async function probeApi() {
  const el = document.getElementById("probe-result");
  el.textContent = "请求中…";
  const start = performance.now();
  try {
    const r = await fetch("/api/work");
    const ms = Math.round(performance.now() - start);
    el.textContent = "HTTP " + r.status + " · " + ms + "ms" + (r.status === 503 ? "  ← 池已打满，业务不可用" : "  ← 业务正常");
  } catch (e) {
    el.textContent = "请求失败（" + Math.round(performance.now() - start) + "ms）：" + e;
  }
}

let toastTimer;
function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg; t.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), 2500);
}

render();
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
"""
