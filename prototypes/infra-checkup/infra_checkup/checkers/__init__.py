#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查器注册表。显式注册，不用动态 import。

原型 1 的教训：@ConditionalOnMissingBean 这类按条件装配在真实进程里评估顺序不可靠，
主路径起不来而集成测试因 @Primary 替身全绿。这里宁可显式、朴素，也不玩自动发现——
少一个检查器必须在启动时炸出来，不能静默少跑一项。
"""

from __future__ import annotations

from typing import Callable, Dict, List

from ..model import Context, Finding
from . import credscan, drift, exposure

Checker = Callable[[Context], List[Finding]]

REGISTRY: Dict[str, Checker] = {
    "credscan": credscan.run,
    "drift": drift.run,
    "exposure": exposure.run,
}

ALL = tuple(REGISTRY.keys())


def get_enabled(names) -> Dict[str, Checker]:
    """按名字取检查器。未知名字直接抛错，绝不静默忽略用户配置。"""
    out: Dict[str, Checker] = {}
    for n in names:
        if n not in REGISTRY:
            raise KeyError("未知检查器 %r；可用：%s" % (n, ", ".join(ALL)))
        out[n] = REGISTRY[n]
    return out
