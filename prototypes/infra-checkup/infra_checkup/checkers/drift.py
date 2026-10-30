#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""配置漂移 / 抓取健康 检查器。数据源：160 Prometheus /api/v1/targets（只读 HTTP）。

核心判定是「主机级聚合 + 自推断」，不依赖人工基线就能发现主机故障：
老运维看到同一台主机上 20 个监控目标同时 down，会直接判断「这台机器挂了」。
这个直觉必须固化进代码——否则工具只是把人已经知道的死 IP 复述一遍，没有价值。

判定顺序（先主机级、后 job 级，避免同一根因重复计数）：
1. 按 instance 的 host 聚合。某 host 下所有 target 全 down 且满足
   （在 known_dead_hosts 基线里）或（是可路由 IP 且目标数 >= 2）→ 报【1 条 P1】：
   - 命中基线：措辞为「已确认失联主机」
   - 仅数据自推断：措辞为「疑似主机级失联」，并明确标注这是推断、需人工核实
2. 未被主机级覆盖的 down target，按 job 聚合：
   - job 有 >=2 个目标且全 down → P1「job 整体失明」
   - 否则 → P2「单/部分实例 down」，交人工确认，不武断升级
3. expected_targets 基线非空时才做清单增删比对；空则跳过（由 collector 登记 degraded）
"""

from __future__ import annotations

import re
from collections import defaultdict
from typing import Any, Dict, List, Set, Tuple

from ..model import Context, Finding

_HOST_RE = re.compile(r"^([^:]+):(\d+)$")

# 单标签容器名（clickhouse、node-exporter…）不算可路由 IP，不能据此推断主机级故障；
# 只有点分四段纯数字才按 IP 处理。
def _is_ip(host: str) -> bool:
    parts = host.split(".")
    return len(parts) == 4 and all(p.isdigit() for p in parts)


def _host_of(instance: str) -> str:
    m = _HOST_RE.match(instance.strip())
    return m.group(1) if m else instance.strip()


def _clip(text: str, limit: int) -> str:
    """截断长文本并显式标注省略，避免证据看起来像断句。"""
    text = text.strip()
    return text if len(text) <= limit else text[:limit] + "…"


# Docker 内嵌 DNS 解析不到目标容器名。这不是「服务刚好挂了」，而是配置里
# 写了一个根本不存在的容器/主机 —— 与「指向失联主机」同属配置漂移，必须升 P1。
# 人工核对 160 真数据时抓到：clickhouse/kafka/qdrant/rocketmq 四个目标全是
# `lookup X on 127.0.0.11:53: no such host`，我原先把它们降成 P2「需人工确认」是误分级。
_DNS_NO_SUCH_HOST = re.compile(r"(?i)no such host|lookup .* on 127\.0\.0\.11|name or service not known|temporary failure in name resolution")


def _is_dns_misconfig(t: Dict[str, Any]) -> bool:
    return bool(_DNS_NO_SUCH_HOST.search(t.get("lastError", "") or ""))


def run(ctx: Context) -> List[Finding]:
    findings: List[Finding] = []
    targets = ctx.targets
    if not targets:
        return findings

    known_dead: Set[str] = set(ctx.baseline.get("known_dead_hosts", []) or [])
    expected = ctx.baseline.get("expected_targets", None)

    by_host: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for t in targets:
        h = _host_of(t.get("instance", ""))
        if h:
            by_host[h].append(t)

    covered_instances: Set[str] = set()

    # ── 1. 主机级聚合（自推断优先，基线仅作确认增强）──
    for host, lst in by_host.items():
        if not all(t.get("health") == "down" for t in lst):
            continue
        n = len(lst)
        confirmed = host in known_dead
        inferred = _is_ip(host) and n >= 2
        if not (confirmed or inferred):
            continue
        jobs = sorted({t.get("job", "") for t in lst})
        sample = "; ".join(
            "%s(%s)" % (t.get("instance", ""), t.get("health", "")) for t in lst[:5]
        ) + (" …" if n > 5 else "")
        if confirmed:
            title = "已确认失联主机：%s（%d 个监控目标全不可达）" % (host, n)
            why = ("该主机在 known_dead_hosts 基线中被人工确认失联，其下 %d 个抓取目标仍在配置里；"
                   "抓取永远失败且持续产生无效告警，应及时清理或更新服务发现" % n)
        else:
            title = "疑似主机级失联：%s（%d 个监控目标同时 down，数据自推断）" % (host, n)
            why = ("同一 IP 上 %d 个目标同时不可达，主机级故障的概率远高于各服务同时故障；"
                   "这是从抓取健康数据自主推断的结论，不依赖人工基线，但需人工核实主机是否真的下线/IP 是否变更" % n)
        findings.append(Finding(
            id="drift:hostdown:%s" % host,
            checker="drift", severity="P1", category="drift",
            title=title,
            evidence="host=%s jobs=%s targets=%d [%s]" % (host, jobs, n, sample),
            why=why,
            suggestion="核实主机是否下线或 IP 变更；确认后清理陈旧 target 或修正服务发现，"
                       "并把结果回填基线（若确属失联）",
            meta={"host": host, "target_count": n, "jobs": jobs,
                  "confirmed_by_baseline": confirmed, "self_inferred": not confirmed},
        ))
        for t in lst:
            covered_instances.add(t.get("instance", ""))

    # ── 2. job 级：只处理未被主机级覆盖的 down 目标 ──
    down_by_job: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for t in targets:
        if t.get("instance", "") in covered_instances:
            continue
        if t.get("health") == "down":
            down_by_job[t.get("job", "")].append(t)

    all_by_job: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for t in targets:
        all_by_job[t.get("job", "")].append(t)

    for job, lst in down_by_job.items():
        job_all = all_by_job.get(job, [])
        job_all_down = len(job_all) >= 2 and all(x.get("health") == "down" for x in job_all)
        dns_misconfig = [x for x in lst if _is_dns_misconfig(x)]
        if dns_misconfig:
            # DNS「no such host」= 配置里写了不存在的容器名，是确定性配置错误，
            # 不是「服务可能临时挂了」。人工核对真数据时确认了这一点，升 P1。
            findings.append(Finding(
                id="drift:dnsconfig:%s" % job,
                checker="drift", severity="P1", category="drift",
                title="抓取目标容器名解析失败（配置漂移）：%s" % job,
                evidence="; ".join("%s lastError=%s" %
                                   (x.get("instance", ""),
                                    _clip(x.get("lastError", "") or "", 140))
                                   for x in dns_misconfig[:6])
                          + (" …" if len(dns_misconfig) > 6 else ""),
                why="Docker 内嵌 DNS 返回 no such host，说明服务发现/静态配置里写了"
                    "一个当前网络中不存在的容器或主机名——与「指向失联主机」同属配置漂移，"
                    "是确定性错误而非服务临时故障",
                suggestion="核对 compose 服务名与服务发现配置：容器是否根本没部署、"
                           "名字是否写错；确认后修正服务发现或移除陈旧 target",
                meta={"job": job, "target_count": len(dns_misconfig), "root_cause": "dns_no_such_host"},
            ))
            continue
        if job_all_down:
            findings.append(Finding(
                id="drift:jobdown:%s" % job,
                checker="drift", severity="P1", category="drift",
                title="job 整体失明：%s（%d 个目标全不可达）" % (job, len(job_all)),
                evidence="; ".join("%s(%s)" % (x.get("instance", ""), x.get("health", ""))
                                   for x in job_all[:6]) + (" …" if len(job_all) > 6 else ""),
                why="该 job 没有任何可用目标，对应监控维度整体失明",
                suggestion="检查该 exporter 服务是否存活、端口是否变更",
                meta={"job": job, "target_count": len(job_all)},
            ))
        else:
            findings.append(Finding(
                id="drift:partialdown:%s" % job,
                checker="drift", severity="P2", category="drift",
                title="抓取目标 down：%s（%d 个）" % (job, len(lst)),
                evidence="; ".join("%s lastError=%s" %
                                   (x.get("instance", ""),
                                    _clip(x.get("lastError", "") or "", 160))
                                   for x in lst[:6]) + (" …" if len(lst) > 6 else ""),
                why="单个或部分实例探测为 down，可能是 exporter 未启动、网络或采集侧问题，"
                    "信息不足以判定主机级故障，需人工确认，不直接升 P1",
                suggestion="核对这些实例的 exporter 进程与网络连通性",
                meta={"job": job, "down_count": len(lst)},
            ))

    # ── 3. 基线清单增删比对（仅 expected_targets 非空时）──
    if isinstance(expected, list) and expected:
        seen = {t.get("instance", "") for t in targets}
        expected_set = set(expected)
        for instance in sorted(seen - expected_set):
            findings.append(Finding(
                id="drift:unexpected:%s" % instance,
                checker="drift", severity="P2", category="drift",
                title="基线外的抓取目标：%s" % instance,
                evidence="instance=%s 不在基线 expected_targets" % instance,
                why="可能是新增服务漏登记基线，也可能是误配置，需人工确认后补基线",
                suggestion="确认后更新基线，或移除多余 target",
                meta={"instance": instance},
            ))
        for instance in sorted(expected_set - seen):
            findings.append(Finding(
                id="drift:missing:%s" % instance,
                checker="drift", severity="P1", category="drift",
                title="基线声明的目标缺失：%s" % instance,
                evidence="基线 expected_targets 声明了 %s，实际抓取目标中不存在" % instance,
                why="预期被监控的服务失去采集，监控出现盲区",
                suggestion="确认服务是否下线；若仍在运行，检查服务发现或静态配置为何漏掉",
                meta={"instance": instance},
            ))

    return findings
