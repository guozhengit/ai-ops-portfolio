#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统一数据契约。检查器只认 Context 进、Finding 出，任何检查器都不修改被检查对象。

纪律来源：agentscope-learning/tools/secret_scan.py（脱敏、占位符、分层判定）与
hotel-booking/ops/check/schema_guard.py（纯标准库、离线只读、退出码即门禁）。
本模块不含任何仓库外绝对路径字面量，也不 import 第三方依赖。
"""

from __future__ import annotations

import dataclasses
import json
from typing import Any, Dict, Iterable, List, Optional

SEVERITY_ORDER = {"P0": 0, "P1": 1, "P2": 2}
SEVERITIES = tuple(SEVERITY_ORDER.keys())


@dataclasses.dataclass(frozen=True)
class Finding:
    """一条结构化发现。evidence 只放可复核的原始事实（已脱敏），不放结论。"""

    id: str
    checker: str
    severity: str
    category: str
    title: str
    evidence: str
    why: str
    suggestion: str
    meta: Dict[str, Any] = dataclasses.field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.severity not in SEVERITY_ORDER:
            raise ValueError("未知严重级别: %r" % self.severity)

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


@dataclasses.dataclass
class Context:
    """检查器的唯一输入。采集器负责填，检查器只读。

    targets: 160 Prometheus 抓取目标快照，元素含 job/instance/health/labels/scrapeUrl。
    scan_roots: 本地待扫目录（绝对路径由 CLI 传入，代码里不写死）。
    baseline: 基线（目标白名单 / 端口白名单 / 已知失联主机）。
    """

    targets: List[Dict[str, Any]] = dataclasses.field(default_factory=list)
    scan_roots: List[str] = dataclasses.field(default_factory=list)
    baseline: Dict[str, Any] = dataclasses.field(default_factory=dict)
    collected_at: str = ""
    source_note: str = ""
    degraded: List[str] = dataclasses.field(default_factory=list)

    def degrade(self, reason: str) -> None:
        """采集能力缺失时诚实登记，不静默跳过。报告会原样展示这些降级项。"""
        if reason not in self.degraded:
            self.degraded.append(reason)


def sort_findings(findings: Iterable[Finding]) -> List[Finding]:
    """P0→P2，同级按 checker/id 稳定排序，保证报告与 JSONL 顺序可复现。"""
    return sorted(
        findings,
        key=lambda f: (SEVERITY_ORDER[f.severity], f.checker, f.id),
    )


def dedup_findings(findings: Iterable[Finding]) -> List[Finding]:
    """按稳定 id 去重，保留首次出现。同一根因摇身变两个 id 是误报来源。"""
    seen = set()
    out: List[Finding] = []
    for f in findings:
        if f.id in seen:
            continue
        seen.add(f.id)
        out.append(f)
    return out


def counts(findings: Iterable[Finding]) -> Dict[str, int]:
    c = {s: 0 for s in SEVERITIES}
    for f in findings:
        c[f.severity] += 1
    return c


def findings_to_jsonl(findings: Iterable[Finding]) -> str:
    return "\n".join(json.dumps(f.to_dict(), ensure_ascii=False) for f in findings)
