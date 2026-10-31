#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""infra-checkup 命令行入口。

用法：
  python cli.py run --config config/checkup.example.json
  python cli.py run --prometheus http://192.168.1.160:9091 \
                    --baseline config/baseline.example.json --scan-root fixtures \
                    --out reports/
  python cli.py explain <finding-id> --config config/checkup.example.json
  python cli.py verify-scorer          # 元验证：评分器对已知答案必须 100% 判对

退出码：run 时含 P0/P1 返回 1（门禁），仅 P2 返回 0。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime

from infra_checkup import runner, report, advisor
from infra_checkup.model import Context, counts

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_config(path):
    if not path:
        return {}
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _resolve(p):
    """相对路径按本文件所在目录解析，代码里不写死任何仓库外绝对路径。"""
    if not p:
        return p
    return p if os.path.isabs(p) else os.path.normpath(os.path.join(HERE, p))


def cmd_run(args):
    cfg = _load_config(_resolve(args.config))
    prometheus = args.prometheus or cfg.get("prometheus_url")
    baseline = _resolve(args.baseline or cfg.get("baseline_path"))
    roots = args.scan_root or cfg.get("scan_roots", [])
    roots = [_resolve(r) for r in roots]
    enabled = args.checker or cfg.get("checkers") or list(
        __import__("infra_checkup.checkers", fromlist=["ALL"]).ALL
    )

    ctx = runner.build_context(
        prometheus_url=prometheus, baseline_path=baseline,
        scan_roots=roots, timeout=args.timeout,
    )
    findings, check_log = runner.execute(ctx, enabled)

    outdir = _resolve(args.out or cfg.get("out_dir") or "reports")
    os.makedirs(outdir, exist_ok=True)
    md_path = os.path.join(outdir, "checkup-%s.md" % _stamp(ctx))
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(report.render(ctx, findings, check_log))

    jsonl = os.path.join(outdir, "findings.jsonl")
    # 0 条发现也落盘（V2-C2）：空文件是「查了且无发现」的证据，缺文件无法与「没查」区分，
    # 会让证据重放门禁的假阴性检查失效。write_jsonl 追加语义不变。
    runner.write_jsonl(findings, jsonl)

    c = counts(findings)
    print("体检完成：P0=%d P1=%d P2=%d，共 %d 条" %
          (c["P0"], c["P1"], c["P2"], sum(c.values())))
    for d in ctx.degraded:
        print("  [降级] %s" % d)
    print("报告：%s" % md_path)
    if findings:
        print("证据：%s（只追加）" % jsonl)
    return runner.exit_code(findings)


def _stamp(ctx: Context) -> str:
    """文件名用本地时间，避免 UTC 生成的名字看起来像「昨天」。

    报告正文保留带时区的 UTC 采集时间；文件名只是给人看的本机时间戳。
    """
    if ctx.collected_at:
        try:
            # collected_at 形如 2026-10-29T18:27:55+00:00，转成本地时间
            dt = datetime.fromisoformat(ctx.collected_at)
            local = dt.astimezone()
            return local.strftime("%Y%m%dT%H%M%S")
        except (ValueError, TypeError):
            pass
    return datetime.now().strftime("%Y%m%dT%H%M%S")


def _all_findings(args):
    cfg = _load_config(_resolve(args.config))
    ctx = runner.build_context(
        prometheus_url=args.prometheus or cfg.get("prometheus_url"),
        baseline_path=_resolve(args.baseline or cfg.get("baseline_path")),
        scan_roots=[_resolve(r) for r in (args.scan_root or cfg.get("scan_roots", []))],
        timeout=args.timeout,
    )
    enabled = cfg.get("checkers") or list(
        __import__("infra_checkup.checkers", fromlist=["ALL"]).ALL
    )
    findings, _ = runner.execute(ctx, enabled)
    return findings


def cmd_explain(args):
    findings = _all_findings(args)
    match = [f for f in findings if f.id == args.finding_id]
    if not match:
        # id 可能有前缀差异，做一次包含匹配兜底
        match = [f for f in findings if args.finding_id in f.id]
    if not match:
        print("找不到 finding：%s（先跑一次 run 看报告里的 id）" % args.finding_id)
        return 2
    f = match[0]
    print("追问：%s\n  %s\n" % (f.id, f.title))
    res = advisor.advise(f, question=args.question or "这条具体怎么排查和修复？")
    print("状态：%s  模型：%s  通道：%s" %
          (res["status"], res.get("model") or "无", res.get("transport") or "无"))
    if res.get("latency_ms") is not None:
        print("耗时：%s ms" % res["latency_ms"])
    print("说明：%s" % res["reason"])
    if res["answer"]:
        print("\n--- 模型生成（未经外部验证）---\n%s" % res["answer"])
    return 0 if res["status"] == "ok" else 0  # 降级不阻断 CLI


def cmd_verify_scorer(args):
    from scoring.score import self_test
    ok = self_test()
    print("评分器元验证：%s" % ("通过" if ok else "失败"))
    return 0 if ok else 1


def main(argv=None):
    p = argparse.ArgumentParser(description="基础设施体检 Agent（只读）")
    p.add_argument("--timeout", type=float, default=8.0)
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="跑一轮体检")
    r.add_argument("--config")
    r.add_argument("--prometheus")
    r.add_argument("--baseline")
    r.add_argument("--scan-root", action="append", default=[])
    r.add_argument("--checker", action="append", default=[])
    r.add_argument("--out")
    r.set_defaults(func=cmd_run)

    e = sub.add_parser("explain", help="就某条发现追问怎么修")
    e.add_argument("finding_id")
    e.add_argument("--config")
    e.add_argument("--prometheus")
    e.add_argument("--baseline")
    e.add_argument("--scan-root", action="append", default=[])
    e.add_argument("--question")
    e.set_defaults(func=cmd_explain)

    v = sub.add_parser("verify-scorer", help="评分器元验证")
    v.set_defaults(func=cmd_verify_scorer)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
