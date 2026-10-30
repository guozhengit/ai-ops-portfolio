#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Markdown 报告渲染。

反 AI 味纪律（小郭 2026-10-29 反馈）：不要工整小标题堆砌、不要表格成瘾、
数据嵌在句子里、敢写「没做成/不知道」。报告是给运维本人看的，不是给 PPT 看的。

诚实性硬约束：评测靶场里故意埋的假凭据必须与真机发现分区呈现。
把假数据当成真 P0 报出去，恰恰违反这个原型主打的「诚实」价值观。
"""

from __future__ import annotations

from typing import Any, Dict, List

from .model import Context, Finding, counts

SEV_LABEL = {"P0": "P0 立即处理", "P1": "P1 需确认", "P2": "P2 观察"}


def _split(findings: List[Finding]):
    demo = [f for f in findings if f.meta.get("demo_fixture")]
    real = [f for f in findings if not f.meta.get("demo_fixture")]
    return real, demo


def render(ctx: Context, findings: List[Finding], check_log: List[Dict[str, Any]]) -> str:
    real, demo = _split(findings)
    c = counts(real)
    dc = counts(demo)
    lines: List[str] = []

    lines.append("# 基础设施体检报告")
    lines.append("")
    lines.append("采集时间 %s。%s。"
                 % (ctx.collected_at or "未知",
                    ctx.source_note or "本次没有成功采集到任何远程数据源"))
    lines.append("")

    # 降级项写在最前面：能力边界比结论更重要，先说清楚哪里没查
    if ctx.degraded:
        lines.append("## 这次没查全的地方")
        lines.append("")
        for d in ctx.degraded:
            lines.append("- %s" % d)
        lines.append("")

    lines.append("## 结论")
    lines.append("")
    total = sum(c.values())
    if total == 0:
        lines.append("真实环境本轮没发现问题。注意这只覆盖下面列出来的检查项和数据源，"
                     "不等于环境绝对安全——没查全的地方见上。")
    else:
        lines.append("真实环境本轮 %d 条发现，其中 P0 %d 条、P1 %d 条、P2 %d 条。"
                     % (total, c["P0"], c["P1"], c["P2"]))
        if c["P0"]:
            lines.append("P0 是需要马上核实的：明文凭据或高危服务端口，"
                         "但「高危端口」这一项只说明它值得核实，不代表已确认对公网暴露。")
    if demo:
        lines.append("另有 %d 条来自**评测靶场**（fixtures，故意埋的假数据，用于验证检查器能抓到），"
                     "不计入上面的真实告警数，也不代表真实环境存在问题。" % len(demo))
    lines.append("")

    lines.append("检查器执行明细：")
    for entry in check_log:
        if entry["status"] == "ok":
            lines.append("- %s：跑了，产出 %d 条" % (entry["checker"], entry["count"]))
        else:
            lines.append("- %s：**失败**（%s）" % (entry["checker"], entry.get("error", "?")))
    lines.append("")

    lines.append("## 真实环境发现")
    lines.append("")
    if not real:
        lines.append("本轮真实环境无发现（覆盖范围内）。")
        lines.append("")
    else:
        for sev in ("P0", "P1", "P2"):
            group = [f for f in real if f.severity == sev]
            if not group:
                continue
            lines.append("### %s（%d）" % (SEV_LABEL[sev], len(group)))
            lines.append("")
            for f in group:
                lines.extend(_finding_block(f, sev))
        lines.append("")

    if demo:
        lines.append("## 评测靶场命中（故意埋的假数据，非真实告警）")
        lines.append("")
        lines.append("这些是 fixtures 里预置的用例，用来证明检查器按预期工作；"
                     "真实环境体检时跳过 fixtures 目录，不会出现在这里。")
        lines.append("")
        for sev in ("P0", "P1", "P2"):
            group = [f for f in demo if f.severity == sev]
            if not group:
                continue
            lines.append("### %s · 靶场（%d）" % (SEV_LABEL[sev], len(group)))
            lines.append("")
            for f in group:
                lines.extend(_finding_block(f, sev))

    lines.append("---")
    lines.append("")
    lines.append("证据链同步落 JSONL（只追加，每条带 demo_fixture 标记）。"
                 "本报告由程序渲染，检查器不修改任何被检查对象；"
                 "追问具体条目怎么修，用 `python cli.py explain <finding-id>`。")
    return "\n".join(lines)


def _finding_block(f: Finding, sev: str) -> List[str]:
    return [
        "**%s**" % f.title,
        "",
        "`%s` · %s · %s" % (f.id, f.checker, f.category),
        "",
        "看到的：%s" % f.evidence,
        "",
        "为什么算 %s：%s" % (sev, f.why),
        "",
        "建议：%s" % f.suggestion,
        "",
    ]
