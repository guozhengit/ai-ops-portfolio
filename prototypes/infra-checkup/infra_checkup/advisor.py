#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""追问链：就某条 finding 用自然语言问「怎么修」，走方舟 plan 真模型。

纪律：
1. 模型不可用/报错/超时 → 如实降级并标注，绝不编一个像模像样的答案假装成功。
2. 反注入自检——finding 正文来自被扫描的环境（不可信输入），追问前先检测注入模式，
   命中就拒绝把这些文本拼进 prompt。原型 1 L21 的 lead 做过同样的反注入自检。
3. 模型输出原样标注为「模型生成，未经外部验证」，跟确定性检查器结果严格区分。
4. Key 只从环境变量取，不写死、不打印。
"""

from __future__ import annotations

import json
import os
import re
import urllib.request
import urllib.error
from typing import Any, Dict, Optional

from .model import Finding

INJECTION_PATTERNS = [
    r"(?i)ignore (all |the )?previous instructions",
    r"(?i)disregard .*instructions",
    r"(?i)you are now",
    r"(?i)system prompt",
    r"(?i)忽略(以上|之前|前面).*指令",
    r"(?i)你现在是",
]

DEFAULT_MODEL = "doubao-seed-2.1-turbo"


def _detect_injection(text: str) -> Optional[str]:
    for pat in INJECTION_PATTERNS:
        if re.search(pat, text):
            return pat
    return None


def _env(name: str) -> str:
    return (os.environ.get(name) or "").strip()


def advise(finding: Finding, question: str = "", timeout: float = 40.0) -> Dict[str, Any]:
    """返回 {status: ok|degraded|blocked, answer, model, latency_ms, reason}.

    绝不抛异常终止 CLI；任何失败都转成 degraded 并带上可读原因。
    """
    # 反注入自检：把 finding 的不可信字段和用户的 question 都过一遍
    untrusted = " ".join([finding.evidence, finding.title, finding.why, question or ""])
    hit = _detect_injection(untrusted)
    if hit:
        return {
            "status": "blocked",
            "answer": "",
            "model": None,
            "reason": "反注入自检命中(%s)：被扫描环境的内容不可信，拒绝把它拼进模型 prompt。"
                      "请人工核对原始文件后再决定是否追问。" % hit,
        }

    api_key = _env("ARK_API_KEY")
    base_url = _env("ARK_BASE_URL") or "https://ark.cn-beijing.volces.com/api/v1"
    model = _env("ARK_MODEL") or DEFAULT_MODEL
    if not api_key or api_key.lower().startswith("your_"):
        return {
            "status": "degraded", "answer": "", "model": None,
            "reason": "未配置真实 ARK_API_KEY（当前为空或占位符），追问链降级。"
                      "确定性检查器结果不受影响；要启用追问请先在 .env 填真实 key 再 export。",
        }

    prompt = (
        "你是资深运维工程师。下面是一条自动化体检工具产出的发现，"
        "请只针对这一条给出可执行的排查与修复步骤，分点、简短、不要泛泛而谈。"
        "如果信息不足以判断，直接说「证据不足，需要先确认 X」，不要编造。\n\n"
        "【发现】%s\n【证据】%s\n【为什么定级】%s\n"
        % (finding.title, finding.evidence, finding.why)
    )
    if question:
        prompt += "\n【追问】%s\n" % question

    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": "你是严谨的 SRE，只给可执行的步骤，信息不足就直说。"},
            {"role": "user", "content": prompt},
        ],
        # doubao-seed 是推理模型：不关深度思考会先耗十几秒到几分钟想，且 reasoning
        # tokens 会吃光正文（原型 2 踩过 max_tokens=64 截断）。实测加 thinking:disabled
        # 后 10s 出完整回答、reasoning_content=None。
        "max_tokens": 800,
        "thinking": {"type": "disabled"},
        "stream": False,
    }).encode("utf-8")

    url = base_url.rstrip("/") + "/chat/completions"
    import time
    t0 = time.time()

    def _parse(payload_obj):
        choices = payload_obj.get("choices") or []
        return (choices[0].get("message", {}).get("content", "") if choices else "")

    def _finish(payload_obj, transport):
        latency = int((time.time() - t0) * 1000)
        answer = _parse(payload_obj)
        if not answer:
            return {"status": "degraded", "answer": "", "model": model,
                    "latency_ms": latency, "reason": "模型返回空内容，追问降级。",
                    "transport": transport}
        return {"status": "ok", "answer": answer.strip(), "model": model,
                "latency_ms": latency,
                "reason": "模型生成，未经外部验证，请人工判断后再执行。",
                "transport": transport}

    # 主通道：curl 子进程。本环境实测 urllib 走环境代理会卡到超时（外网代理栈差异），
    # curl 走同一代理 1.8s 即 200。key 通过 -K 临时配置文件传入，绝不进 argv（进程列表可见）。
    import shutil
    import subprocess
    import tempfile
    if shutil.which("curl"):
        header_path = None
        try:
            fd, header_path = tempfile.mkstemp(prefix="ic-ark-hdr-", suffix=".txt")
            os.close(fd)
            try:
                os.chmod(header_path, 0o600)
            except OSError:
                pass
            with open(header_path, "w", encoding="utf-8") as hf:
                hf.write('header = "Authorization: Bearer %s"\n' % api_key)
                hf.write('header = "Content-Type: application/json"\n')
            proc = subprocess.run(
                ["curl", "-sS", "-K", header_path, "-X", "POST", url,
                 "--data-binary", "@-", "-w", "\n__IC_HTTP__%{http_code}"],
                input=body, capture_output=True, timeout=timeout,
            )
            raw = proc.stdout.decode("utf-8", "ignore")
            marker = "\n__IC_HTTP__"
            text, _, code = raw.partition(marker)
            http_code = code.strip()
            if proc.returncode != 0:
                err = proc.stderr.decode("utf-8", "ignore")[:200]
                return {"status": "degraded", "answer": "", "model": model,
                        "reason": "curl 调用失败(rc=%d: %s)，追问降级，未编造答案。"
                                  % (proc.returncode, err), "transport": "curl"}
            if http_code and not http_code.startswith("2"):
                return {"status": "degraded", "answer": "", "model": model,
                        "reason": "上游 HTTP %s：%s" % (http_code, text[:300]),
                        "transport": "curl"}
            return _finish(json.loads(text), "curl")
        except subprocess.TimeoutExpired:
            return {"status": "degraded", "answer": "", "model": model,
                    "reason": "curl 调用超时，追问降级，未编造答案。", "transport": "curl"}
        except (OSError, ValueError) as exc:
            return {"status": "degraded", "answer": "", "model": model,
                    "reason": "curl 通道异常(%s: %s)，追问降级，未编造答案。"
                              % (type(exc).__name__, exc), "transport": "curl"}
        finally:
            if header_path and os.path.exists(header_path):
                try:
                    os.remove(header_path)
                except OSError:
                    pass

    # 兜底：没有 curl 时才用 urllib（本环境已实测它走代理会卡，超时给短一点，不傻等）。
    try:
        req = urllib.request.Request(
            url, data=body,
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + api_key},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=min(timeout, 20.0)) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        return _finish(payload, "urllib")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")[:300]
        return {"status": "degraded", "answer": "", "model": model,
                "reason": "上游 HTTP %s：%s" % (exc.code, detail), "transport": "urllib"}
    except (urllib.error.URLError, OSError, ValueError) as exc:
        return {"status": "degraded", "answer": "", "model": model,
                "reason": "urllib 兜底调用失败(%s: %s)，追问降级，未编造答案。"
                          % (type(exc).__name__, exc), "transport": "urllib"}
