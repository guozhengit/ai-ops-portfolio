#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 160 真机跑一轮，输出「人工黄金答案 vs 工具产出」的漏报/误报对照。

这是外部裁判的执行入口：读 groundtruth-160.json（人工逐条核对），
现场采集 160 真数据，跑 drift，再用 scoring/score.py 算 precision/recall。
评分器自身先过 self_test（元验证），否则不评原型。

用法：python scoring/run_score.py [--offline] [--offline-file PATH] [--out PATH]
  --offline          不连 160，改用最近一次 reports/local/findings.jsonl 里的 drift 结果
  --offline-file PATH  证据重放门禁用：从指定留档 findings 文件重建评分（写 --out，不碰原件）
  --out PATH         结果 JSON 输出路径（默认 scoring/score-result-160.json）
"""

from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

from scoring.score import self_test, score  # noqa: E402
from infra_checkup.model import Context  # noqa: E402
from infra_checkup.checkers import drift  # noqa: E402


def _load_golden():
    with open(os.path.join(HERE, "groundtruth-160.json"), "r", encoding="utf-8") as fh:
        return json.load(fh)


def _live_drift():
    from infra_checkup.collector import fetch_prometheus_targets, load_baseline
    cfg = json.load(open(os.path.join(ROOT, "config", "checkup.example.json"), encoding="utf-8"))
    base = load_baseline(os.path.join(ROOT, cfg["baseline_path"]))
    ts = fetch_prometheus_targets(cfg["prometheus_url"])
    return ts, drift.run(Context(targets=ts, baseline=base))


def _offline_drift(path=None):
    path = path or os.path.join(ROOT, "reports", "local", "findings.jsonl")
    out = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            f = json.loads(line)
            if f.get("checker") == "drift":
                out.append(f)
    return None, out


def _arg_value(flag):
    """手工取 --flag VALUE（参数量小，不值得引入 argparse 双轨）。"""
    if flag in sys.argv:
        i = sys.argv.index(flag)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return None


def main():
    offline_file = _arg_value("--offline-file")
    offline = "--offline" in sys.argv or offline_file is not None
    if not self_test():
        print("评分器元验证未通过，拒绝评分（先修评分器）")
        return 1

    golden_doc = _load_golden()
    golden = golden_doc["golden_findings"]

    if offline_file:
        ts, findings = _offline_drift(offline_file)
        reported = {f["id"]: f["severity"] for f in findings}
        source = "offline-file:%s" % offline_file
    elif offline:
        ts, findings = _offline_drift()
        reported = {f["id"]: f["severity"] for f in findings}
        source = "offline(reports/local/findings.jsonl)"
    else:
        ts, findings = _live_drift()
        reported = {f.id: f.severity for f in findings}
        source = "live(160)"

    result = score(set(reported), reported, golden)

    print("=" * 66)
    print("外部裁判：160 真机 drift 维度 · 漏报/误报对照")
    print("=" * 66)
    print("黄金答案来源：%s" % golden_doc["source"])
    print("工具产出来源：%s" % source)
    if ts is not None:
        print("activeTargets：%d（人工核对时为 %d）" % (len(ts), golden_doc["total_targets"]))
    print("-" * 66)
    print("应报 %d 条，工具报 %d 条" % (result["golden_count"], result["reported_count"]))
    print("命中 TP=%d  漏报 FN=%d  误报 FP=%d" %
          (result["tp"], result["fn"], result["fp"]))
    print("precision=%.3f  recall=%.3f" % (result["precision"], result["recall"]))
    if result["false_negative_漏报"]:
        print("  漏报：%s" % result["false_negative_漏报"])
    if result["false_positive_误报"]:
        print("  误报：%s" % result["false_positive_误报"])
    if result["severity_mismatch"]:
        print("  分级不一致：%s" % result["severity_mismatch"])
    print("-" * 66)
    print("口径与局限：")
    for lim in golden_doc["known_limitations"]:
        print("  - %s" % lim)
    print("=" * 66)

    # 落一份 JSON 结果，供 verification/ 汇总读取（重放门禁用 --out 写临时目录，不碰入库原件）
    out_path = _arg_value("--out") or os.path.join(HERE, "score-result-160.json")
    payload = {
        "source": source,
        "golden_source": golden_doc["source"],
        "result": result,
        "limitations": golden_doc["known_limitations"],
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    print("评分结果已写入：%s" % out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
