"""verify_channels.py —— 多模型渠道接入验证（原型 2 前置，W2 素材）。

用法：python verify_channels.py
- Key 从仓库根目录 .env 读取（gitignored，严禁硬编码/入库）
- 输出只回显 Key 尾 4 位；完整报告写 data/channel-report.json（gitignored）
- 每渠道两步：GET /models（能列出→凭证与端点正确）+ 最小 chat 调用（端到端可用）
"""

import json
import os
import time
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT / ".env"
REPORT_PATH = ROOT / "data" / "channel-report.json"
TIMEOUT = 40


def load_env() -> dict:
    env = {}
    for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    return env


def mask(key: str) -> str:
    return "…" + key[-4:] if key and key != "未配置" else "未配置"


def http(method: str, url: str, key: str, body: dict | None = None):
    data = json.dumps(body).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {key}")
    if data:
        req.add_header("Content-Type", "application/json")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 绕过失效代理直连
    start = time.perf_counter()
    try:
        with opener.open(req, timeout=TIMEOUT) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            return resp.status, payload, int((time.perf_counter() - start) * 1000)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:300]
        return e.code, {"error": detail}, int((time.perf_counter() - start) * 1000)
    except Exception as e:  # noqa: BLE001
        return None, {"error": f"{type(e).__name__}: {e}"}, int((time.perf_counter() - start) * 1000)


def list_model_ids(base: str, key: str):
    status, payload, ms = http("GET", f"{base}/models", key)
    if status != 200:
        return None, f"HTTP {status}: {payload.get('error', '')[:120]}"
    ids = [m.get("id") for m in payload.get("data", []) if m.get("id")]
    return ids, None


def chat(base: str, key: str, model: str):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": "只回复两个字：正常"}],
        "max_tokens": 64,
        "stream": False,
    }
    status, payload, ms = http("POST", f"{base}/chat/completions", key, body)
    if status != 200:
        return {"ok": False, "http": status, "latency_ms": ms, "error": str(payload.get("error", ""))[:200]}
    choice = (payload.get("choices") or [{}])[0]
    content = ((choice.get("message") or {}).get("content") or "").strip()
    usage = payload.get("usage", {})
    return {
        "ok": True, "http": status, "latency_ms": ms, "reply": content[:60],
        "prompt_tokens": usage.get("prompt_tokens"), "completion_tokens": usage.get("completion_tokens"),
    }


def verify(name: str, base: str, key: str, prefer_model: str | None, pick: "callable | None" = None):
    print(f"\n===== {name} =====")
    print(f"  base_url : {base}")
    print(f"  key      : {mask(key)}")
    result = {"name": name, "base_url": base}

    ids, err = list_model_ids(base, key)
    if ids is not None:
        print(f"  /models  : OK（{len(ids)} 个模型）")
        result["models_count"] = len(ids)
        result["models_sample"] = ids[:12]
    else:
        print(f"  /models  : FAIL（{err}）——部分平台无此端点，继续 chat 直测")
        result["models_error"] = err

    model = prefer_model
    if model is None and ids and pick:
        model = pick(ids)
    if model is None:
        result["chat"] = {"ok": False, "error": "无可用模型名"}
        print("  chat     : SKIP（无法确定模型名）")
        return result

    print(f"  chat     : model={model}")
    r = chat(base, key, model)
    result["model"] = model
    result["chat"] = r
    if r["ok"]:
        print(f"  chat     : OK {r['latency_ms']}ms 回复「{r['reply']}」 tokens={r['prompt_tokens']}/{r['completion_tokens']}")
    else:
        print(f"  chat     : FAIL {r.get('http')} {r.get('error', '')[:160]}")
    return result


def main():
    env = load_env()
    REPORT_PATH.parent.mkdir(exist_ok=True)
    results = []

    # 1. DeepSeek（官方）
    k = env.get("DEEPSEEK_API_KEY", "")
    if k and not k.startswith("your_"):
        results.append(verify("DeepSeek", env.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                              k, "deepseek-flash"))

    # 2. 火山方舟（豆包）
    k = env.get("ARK_API_KEY", "")
    if k and not k.startswith("your_"):
        results.append(verify("火山方舟(豆包)", env.get("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"),
                              k, "doubao-seed-2-1-pro-260915"))

    # 3. 小米 MiMo（V2.5 已下线，从 /models 挑 v2.6 系）
    k = env.get("XIAOMI_API_KEY", "")
    if k and not k.startswith("your_"):
        results.append(verify("小米 MiMo", env.get("XIAOMI_BASE_URL", "https://api.xiaomimimo.com/v1"),
                              k, None, pick=lambda ids: next(
                                  (m for m in ids if "v2.6-pro" in m.lower() or "v2.6" in m.lower()), ids[0] if ids else None)))

    # 4. MikuAPI（聚合中转，从 /models 挑通用便宜模型）
    k = env.get("MIKU_API_KEY", "")
    if k and not k.startswith("your_"):
        def pick_miku(ids):
            prefs = ["deepseek-chat", "deepseek-v3", "gpt-4o-mini", "glm-4", "claude-3-5-haiku"]
            for p in prefs:
                hit = next((m for m in ids if m == p or m.startswith(p + "@") or p in m.lower()), None)
                if hit:
                    return hit
            return ids[0] if ids else None
        results.append(verify("MikuAPI", env.get("MIKU_BASE_URL", "https://mikuapi.org/v1"), k, None, pick=pick_miku))

    REPORT_PATH.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n===== 汇总 =====")
    for r in results:
        c = r.get("chat", {})
        status = "PASS" if c.get("ok") else "FAIL"
        print(f"  [{status}] {r['name']:<14} model={r.get('model', '-')}")
    print(f"\n报告已写: {REPORT_PATH}")


if __name__ == "__main__":
    main()
