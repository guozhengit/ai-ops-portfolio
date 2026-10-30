#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""drift 检查器：构造已知快照，验证主机级自推断 / 基线确认 / job 级降级的分级规则。

刻意用假 target 数据，不依赖真机网络——真机对照另有 reports 记录。
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from infra_checkup.model import Context
from infra_checkup.checkers import drift


def T(job, instance, health):
    return {"job": job, "instance": instance, "health": health,
            "labels": {"job": job, "instance": instance}, "scrapeUrl": "", "lastError": ""}


class TestDriftSelfInfer(unittest.TestCase):
    def test_self_infer_without_baseline(self):
        """无基线也要能从「同 IP 多目标同时 down」自主推断主机级故障（核心卖点）。"""
        targets = [
            T("svc", "192.168.1.99:8101", "down"),
            T("svc", "192.168.1.99:8102", "down"),
            T("svc", "192.168.1.99:8103", "down"),
            T("web", "192.168.1.99:8080", "down"),
        ]
        fs = drift.run(Context(targets=targets, baseline={}))
        host = [f for f in fs if f.id == "drift:hostdown:192.168.1.99"]
        self.assertEqual(len(host), 1)
        self.assertEqual(host[0].severity, "P1")
        self.assertTrue(host[0].meta["self_inferred"])
        self.assertFalse(host[0].meta["confirmed_by_baseline"])
        self.assertEqual(host[0].meta["target_count"], 4)
        # 主机级覆盖后，job 级不应再重复报
        self.assertFalse(any(f.id.startswith("drift:jobdown:") or
                             f.id.startswith("drift:partialdown:") for f in fs))

    def test_baseline_confirmed_changes_wording(self):
        """命中基线 → 措辞为「已确认」，confirmed_by_baseline=True。"""
        targets = [T("svc", "192.168.1.99:8101", "down"),
                   T("svc", "192.168.1.99:8102", "down")]
        fs = drift.run(Context(targets=targets, baseline={"known_dead_hosts": ["192.168.1.99"]}))
        h = [f for f in fs if f.id == "drift:hostdown:192.168.1.99"][0]
        self.assertTrue(h.meta["confirmed_by_baseline"])
        self.assertIn("已确认", h.title)

    def test_single_target_ip_down_not_host_level(self):
        """单目标 down 的 IP 不推断主机级（可能只是该服务挂了），降为 job 级 P2。"""
        fs = drift.run(Context(targets=[T("svc", "192.168.1.99:8101", "down")], baseline={}))
        self.assertFalse(any(f.id.startswith("drift:hostdown:") for f in fs))
        self.assertTrue(any(f.id == "drift:partialdown:svc" and f.severity == "P2" for f in fs))

    def test_container_name_down_is_partial_not_host(self):
        """容器名单目标 down 不算主机级故障，P2 交人工。"""
        fs = drift.run(Context(targets=[T("clickhouse", "clickhouse:9363", "down")], baseline={}))
        self.assertFalse(any(f.id.startswith("drift:hostdown:") for f in fs))
        self.assertTrue(any(f.severity == "P2" for f in fs))

    def test_job_all_down_multi_target_p1(self):
        """同一 job 多目标分布在非 IP 主机且全 down → job 整体失明 P1。"""
        targets = [T("batch", "node-a:9100", "down"), T("batch", "node-b:9100", "down")]
        fs = drift.run(Context(targets=targets, baseline={}))
        self.assertTrue(any(f.id == "drift:jobdown:batch" and f.severity == "P1" for f in fs))

    def test_partial_job_down_is_p2(self):
        """job 里有 up 有 down → 只报部分 P2，不升 P1。"""
        targets = [T("svc", "node-a:9100", "down"), T("svc", "node-b:9100", "up")]
        fs = drift.run(Context(targets=targets, baseline={}))
        self.assertTrue(any(f.id == "drift:partialdown:svc" and f.severity == "P2" for f in fs))
        self.assertFalse(any(f.id == "drift:jobdown:svc" for f in fs))

    def test_dns_no_such_host_is_config_drift_p1(self):
        """人工核对真数据抓到的漏分级：Docker DNS no such host = 配置写了不存在的容器名，
        是确定性配置错误，必须升 P1，不能笼统降成 P2「需人工确认」。"""
        t = T("clickhouse", "clickhouse:9363", "down")
        t["lastError"] = ('Get "http://clickhouse:9363/metrics": dial tcp: '
                          'lookup clickhouse on 127.0.0.11:53: no such host')
        fs = drift.run(Context(targets=[t], baseline={}))
        self.assertTrue(any(f.id == "drift:dnsconfig:clickhouse" and f.severity == "P1" for f in fs))
        self.assertFalse(any(f.id == "drift:partialdown:clickhouse" for f in fs))

    def test_plain_down_without_dns_error_stays_p2(self):
        """没有 DNS 明确证据的普通 down 仍是 P2，不武断升级（与上一个用例对照）。"""
        fs = drift.run(Context(targets=[T("svc", "node-a:9100", "down")], baseline={}))
        self.assertTrue(any(f.id == "drift:partialdown:svc" and f.severity == "P2" for f in fs))

    def test_all_up_no_findings(self):
        fs = drift.run(Context(targets=[T("svc", "h:9100", "up")], baseline={}))
        self.assertEqual(fs, [])

    def test_expected_targets_diff(self):
        """基线非空才做增删比对：缺失 P1、新增 P2。"""
        targets = [T("svc", "h:9100", "up"), T("new", "h:9200", "up")]
        fs = drift.run(Context(targets=targets,
                               baseline={"expected_targets": ["h:9100", "h:9300"]}))
        self.assertTrue(any(f.id == "drift:missing:h:9300" and f.severity == "P1" for f in fs))
        self.assertTrue(any(f.id == "drift:unexpected:h:9200" and f.severity == "P2" for f in fs))

    def test_empty_expected_skips_diff(self):
        """空 expected_targets 不做增删比对，避免把全部真实目标报成新增噪音。"""
        targets = [T("svc", "h:9100", "up")]
        fs = drift.run(Context(targets=targets, baseline={"expected_targets": []}))
        self.assertFalse(any(f.id.startswith("drift:unexpected:") for f in fs))


if __name__ == "__main__":
    unittest.main(verbosity=2)
