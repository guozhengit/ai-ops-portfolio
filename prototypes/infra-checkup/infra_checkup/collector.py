#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""环境上下文采集器。只读，不修改任何被采集对象。

采集源（按 DESIGN.md 实测结论）：
- 160 Prometheus /api/v1/targets —— 只读 HTTP，实测 200、32 个 target
- 本地目录 —— 由 CLI 传入，代码里不写死任何仓库外绝对路径

SSH 免密实测不通（三把私钥全被拒），因此刻意不实现 SSH 采集；
能力缺失时通过 Context.degrade() 诚实登记，绝不静默跳过。
"""

from __future__ import annotations

import json
import urllib.request
import urllib.error
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .model import Context


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fetch_prometheus_targets(
    base_url: str,
    timeout: float = 8.0,
) -> List[Dict[str, Any]]:
    """拉取 Prometheus activeTargets，归一化为 job/instance/health/labels 四元组。

    只读 GET。网络异常向上传播，由调用方决定降级，不在这里吞掉错误。
    base_url 形如 http://192.168.1.160:9091（由 CLI/配置注入）。
    """
    url = base_url.rstrip("/") + "/api/v1/targets"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    # 内网靶场采集必须绕开环境代理：本环境有 HTTP_PROXY，不绕会被代理劫持，
    # 返回一个看似正常的假响应（curl 那次 --noproxy 的坑，这里用 ProxyHandler({}) 复刻）。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("status") != "success":
        raise RuntimeError("Prometheus 返回非 success: %s" % payload.get("status"))
    raw = payload.get("data", {}).get("activeTargets", [])
    out: List[Dict[str, Any]] = []
    for t in raw:
        labels = t.get("labels", {}) or {}
        out.append(
            {
                "job": labels.get("job", ""),
                "instance": labels.get("instance", ""),
                "health": t.get("health", ""),
                "labels": labels,
                "scrapeUrl": t.get("scrapeUrl", ""),
                "lastError": t.get("lastError", ""),
            }
        )
    return out


def load_baseline(path: Optional[str]) -> Dict[str, Any]:
    """读基线 JSON。文件缺失/损坏返回空基线并让调用方登记降级，不伪造基线。"""
    if not path:
        return {}
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            raise ValueError("基线根节点必须是对象")
        return data
    except FileNotFoundError:
        return {}


def collect(
    prometheus_url: Optional[str] = None,
    baseline_path: Optional[str] = None,
    scan_roots: Optional[List[str]] = None,
    timeout: float = 8.0,
) -> Context:
    """组装 Context。任一采集源失败都登记 degraded，而不是让检查器拿到半截真相。

    返回 Context，绝不抛异常给 CLI —— 体检工具本身不该因为一个源挂了就整体崩，
    但「哪个源挂了」必须在报告里可见。
    """
    ctx = Context(
        scan_roots=list(scan_roots or []),
        collected_at=_now_iso(),
    )

    baseline = load_baseline(baseline_path)
    if baseline:
        ctx.baseline = baseline
        if not baseline.get("expected_targets"):
            ctx.degrade("基线 expected_targets 为空：本次不做目标清单增删比对，"
                        "只做失联主机 + job down 判定")
        if not baseline.get("known_dead_hosts"):
            ctx.degrade("基线 known_dead_hosts 为空：失联主机判定未启用")
    elif baseline_path:
        ctx.degrade("基线文件未读到(%s)，drift 检查将只做通用 down 判定，跳过白名单比对" % baseline_path)
    else:
        ctx.degrade("未提供基线文件，drift/exposure 仅做通用判定")

    if prometheus_url:
        try:
            ctx.targets = fetch_prometheus_targets(prometheus_url, timeout=timeout)
            ctx.source_note = "prometheus=%s targets=%d" % (prometheus_url, len(ctx.targets))
        except (urllib.error.URLError, OSError, ValueError, RuntimeError) as exc:
            ctx.degrade("Prometheus 采集失败(%s: %s)，drift/exposure 无数据跳过" % (prometheus_url, exc))
    else:
        ctx.degrade("未提供 Prometheus 地址，drift/exposure 无数据跳过")

    if not ctx.scan_roots:
        ctx.degrade("未提供本地扫描目录，credscan 无数据跳过")

    return ctx
