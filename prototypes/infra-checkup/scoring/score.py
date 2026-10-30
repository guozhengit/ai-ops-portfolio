#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外部裁判：把检查器结果对照黄金答案，算漏报/误报/precision/recall。

对应 verification/README.md 的元验证原则：评分器对已知答案的用例必须 100% 判对，
否则先修评分器再评原型。self_test() 就是这个自检。

匹配口径：以 finding.id == expected.id 为准（id 是稳定去重键）。
严重级别可单列出来比（级别判错不算漏报，但记为 severity_mismatch）。
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Set


def score(finding_ids: Set[str], severity_by_id: Dict[str, str],
          golden: List[Dict[str, Any]]) -> Dict[str, Any]:
    """golden 元素: {"id":..., "severity":...}（该报的）；另有 must_not_report 列表可选。

    返回结构化结果，数字保留可复核的分子分母，不藏。
    """
    expected: Dict[str, str] = {g["id"]: g.get("severity") for g in golden}
    exp_ids = set(expected)
    got_ids = set(finding_ids)

    tp = sorted(exp_ids & got_ids)
    fn = sorted(exp_ids - got_ids)          # 漏报：该报没报
    fp = sorted(got_ids - exp_ids)          # 误报：不该报却报了

    sev_mismatch = []
    for fid in tp:
        want = expected.get(fid)
        if want and severity_by_id.get(fid) != want:
            sev_mismatch.append({"id": fid, "expected": want, "got": severity_by_id.get(fid)})

    precision = len(tp) / len(got_ids) if got_ids else 1.0
    recall = len(tp) / len(exp_ids) if exp_ids else 1.0
    return {
        "true_positive": tp,
        "false_negative_漏报": fn,
        "false_positive_误报": fp,
        "severity_mismatch": sev_mismatch,
        "tp": len(tp), "fn": len(fn), "fp": len(fp),
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "golden_count": len(exp_ids),
        "reported_count": len(got_ids),
    }


def score_findings(findings, golden: List[Dict[str, Any]]) -> Dict[str, Any]:
    ids = {f.id for f in findings}
    sev = {f.id: f.severity for f in findings}
    return score(ids, sev, golden)


def self_test() -> bool:
    """元验证：喂几组已知答案，评分器本身必须判对，否则不敢用它评原型。"""
    cases = [
        # (got_ids, golden, 期望 tp/fn/fp)
        ({"a", "b"}, [{"id": "a"}, {"id": "b"}], (2, 0, 0)),
        ({"a"}, [{"id": "a"}, {"id": "b"}], (1, 1, 0)),          # 漏报 b
        ({"a", "x"}, [{"id": "a"}], (1, 0, 1)),                   # 误报 x
        (set(), [{"id": "a"}], (0, 1, 0)),                        # 全漏
        ({"a"}, [], (0, 0, 1)),                                    # 全误报
    ]
    for ids, golden, want in cases:
        r = score(ids, {i: "P1" for i in ids}, golden)
        got = (r["tp"], r["fn"], r["fp"])
        if got != want:
            print("元验证失败：got=%s want=%s" % (got, want))
            return False
        # precision/recall 边界
        if ids and want[1] == 0 and want[2] == 0 and r["precision"] != 1.0:
            print("元验证失败：precision 应为 1.0")
            return False
    return True


if __name__ == "__main__":
    import sys
    ok = self_test()
    print("评分器元验证：%s" % ("通过" if ok else "失败"))
    sys.exit(0 if ok else 1)
