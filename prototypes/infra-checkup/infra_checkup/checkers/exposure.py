#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""端口暴露面检查器。数据源：160 Prometheus targets 里记录的 instance 端口。

【证据边界，必须诚实】我们只有 Prometheus 正在抓取的端口，不是从外部扫过全端口，
也没验证这些端口是否对公网可达。所以本检查器判定的是「基线一致性」与「高危端口
是否出现在被监控清单里」，措辞绝不写成「已对公网暴露」。真正的暴露验证需要外部扫描，
那是刻意不做的范围（见 DESIGN.md 第五节）。

判定：
- 高危端口（2375/6379/9200/27017/11211/8500 等）出现在 targets 里 → P0，
  提示「若该服务无认证或对公网可达即为高危，需人工核实认证与绑定地址」
- instance 端口不在基线 allowed_ports 白名单 → P1（基线外端口，可能是漏登记）
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Set

from ..model import Context, Finding

_HOST_RE = re.compile(r"^([^:]+):(\d+)$")

# 无认证即可被直接利用/常见的管理端口。命中不等于已暴露，只作为需人工核实的强信号。
HIGH_RISK_PORTS = {
    "2375": "Docker Remote API（未授权可接管宿主机）",
    "2376": "Docker TLS 端口",
    "6379": "Redis（未授权可读写/写 ssh key）",
    "9200": "Elasticsearch（未授权可读写索引）",
    "9300": "Elasticsearch 集群通信",
    "27017": "MongoDB（未授权访问）",
    "11211": "Memcached（未授权/放大攻击）",
    "8500": "Consul（未授权访问）",
    "2181": "ZooKeeper（未授权访问）",
    "8161": "ActiveMQ 管理台",
}


def run(ctx: Context) -> List[Finding]:
    findings: List[Finding] = []
    if not ctx.targets:
        return findings

    allowed: Set[str] = set(str(p) for p in (ctx.baseline.get("allowed_ports", []) or []))
    has_port_baseline = bool(allowed)
    known_dead = set(ctx.baseline.get("known_dead_hosts", []) or [])

    seen: Set[str] = set()
    for t in ctx.targets:
        instance = t.get("instance", "")
        m = _HOST_RE.match(instance.strip())
        if not m:
            continue
        host, port = m.group(1), m.group(2)
        job = t.get("job", "")

        # 指向已失联主机的目标：根因是「配置指向死主机」，drift 已经按 P1 报过。
        # 这里再按「端口不在白名单」报一条就是同一根因重复计数，跳过。
        if known_dead and host in known_dead:
            continue

        if port in HIGH_RISK_PORTS:
            # instance 本身已含端口（10.0.0.5:6379），不要再拼一次 port，
            # 否则 id 变成 ...:6379:6379 这种重复尾巴。
            key = "exposure:highrisk:%s" % instance
            if key not in seen:
                seen.add(key)
                findings.append(Finding(
                    id=key,
                    checker="exposure", severity="P0", category="exposure",
                    title="高危服务端口在被监控清单中：%s" % instance,
                    evidence="job=%s instance=%s —— %s" % (job, instance, HIGH_RISK_PORTS[port]),
                    why="该端口对应服务一旦无认证或对公网可达即可被直接利用；"
                        "这里只依据 Prometheus 抓取记录判定「值得核实」，不等于已确认对公网暴露",
                    suggestion="人工核实：是否启用认证、是否仅绑定内网/127.0.0.1、防火墙是否放行公网；"
                               "确认无误后把该端口登记进基线白名单以消除重复告警",
                    meta={"instance": instance, "port": port, "job": job,
                          "verified_exposure": False},
                ))
            continue

        if has_port_baseline and port not in allowed:
            key = "exposure:unlisted:%s" % instance
            if key in seen:
                continue
            seen.add(key)
            findings.append(Finding(
                id=key,
                checker="exposure", severity="P1", category="exposure",
                title="基线外端口：%s" % instance,
                evidence="job=%s instance=%s 端口 %s 不在 allowed_ports 白名单" % (job, instance, port),
                why="基线未登记的服务端口，可能是新增漏登记或误配置",
                suggestion="确认服务归属后更新基线白名单，或下线多余监听",
                meta={"instance": instance, "port": port, "job": job},
            ))

    return findings
