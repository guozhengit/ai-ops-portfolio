#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""编排层：采集 → 跑检查器 → 去重排序 → 落 JSONL 证据 → 渲染 Markdown。

单个检查器抛异常不拖垮整场，但异常必须作为一条 finding 显式出现在报告里
（而不是被 except 吞掉后假装「这项没问题」）。这是原型 1 D9 那条教训的延续：
静默失败比报错危险得多。
"""

from __future__ import annotations

import os
import traceback
from datetime import datetime, timezone
from typing import Any, Dict, List, Tuple

from .checkers import get_enabled
from .collector import collect
from .model import Context, Finding, counts, dedup_findings, sort_findings


def _ts() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def execute(ctx: Context, enabled) -> Tuple[List[Finding], List[Dict[str, Any]]]:
    """跑检查器。返回 (findings, check_log)。check_log 记录每项跑了/失败/跳过，用于报告自证。"""
    findings: List[Finding] = []
    check_log: List[Dict[str, Any]] = []
    checkers = get_enabled(enabled)

    for name, fn in checkers.items():
        try:
            produced = fn(ctx)
            produced = list(produced or [])
            findings.extend(produced)
            check_log.append({"checker": name, "status": "ok", "count": len(produced)})
        except Exception as exc:  # noqa: BLE001 —— 检查器故障必须可见，不能静默
            # 检查器自身崩了，是一条 P1 元问题：体检能力残缺，而不是「没发现问题」。
            err = "%s: %s" % (type(exc).__name__, exc)
            findings.append(Finding(
                id="meta:checker-error:%s" % name,
                checker="meta", severity="P1", category="meta",
                title="检查器执行失败：%s" % name,
                evidence=err,
                why="该检查器未成功完成，其覆盖范围本次体检实际为空；不报出来等于假装检查过",
                suggestion="查看异常堆栈修复检查器后重跑",
                meta={"traceback": traceback.format_exc(limit=4)},
            ))
            check_log.append({"checker": name, "status": "error", "error": err})

    findings = sort_findings(dedup_findings(findings))
    return findings, check_log


def build_context(
    prometheus_url=None,
    baseline_path=None,
    scan_roots=None,
    timeout: float = 8.0,
) -> Context:
    return collect(
        prometheus_url=prometheus_url,
        baseline_path=baseline_path,
        scan_roots=scan_roots,
        timeout=timeout,
    )


def write_jsonl(findings: List[Finding], path: str) -> str:
    """证据链只追加，不改写（对齐原型 1 incidents.jsonl、原型 2 usage.jsonl 纪律）。"""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    import json
    with open(path, "a", encoding="utf-8") as fh:
        for f in findings:
            fh.write(json.dumps(f.to_dict(), ensure_ascii=False) + "\n")
    return path


def exit_code(findings: List[Finding]) -> int:
    """退出码即门禁：有 P0 或 P1 返回 1，供 CI/cron 阻断；仅 P2 返回 0。"""
    c = counts(findings)
    return 1 if (c["P0"] or c["P1"]) else 0
