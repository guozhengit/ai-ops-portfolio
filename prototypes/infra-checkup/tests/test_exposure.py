#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""exposure 检查器：高危端口 P0 / 基线外端口 P1 / 失联主机目标跳过 / 证据边界诚实。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from infra_checkup.model import Context
from infra_checkup.checkers import exposure


def T(job, instance):
    return {"job": job, "instance": instance, "health": "up",
            "labels": {"job": job, "instance": instance}}


class TestExposure(unittest.TestCase):
    def test_high_risk_port_p0(self):
        fs = exposure.run(Context(targets=[T("redis", "10.0.0.5:6379")], baseline={}))
        self.assertTrue(any(f.id == "exposure:highrisk:10.0.0.5:6379" and f.severity == "P0"
                            for f in fs))

    def test_high_risk_flag_not_claimed_as_confirmed_exposure(self):
        """措辞诚实：verified_exposure=False 且 why 明说不等于已确认对公网暴露。"""
        fs = exposure.run(Context(targets=[T("redis", "10.0.0.5:6379")], baseline={}))
        f = [x for x in fs if x.id == "exposure:highrisk:10.0.0.5:6379"][0]
        self.assertFalse(f.meta["verified_exposure"])
        self.assertIn("不等于已确认对公网暴露", f.why)

    def test_unlisted_port_p1_when_baseline_present(self):
        fs = exposure.run(Context(targets=[T("svc", "h:8080")],
                                  baseline={"allowed_ports": ["9100"]}))
        self.assertTrue(any(f.id == "exposure:unlisted:h:8080" and f.severity == "P1" for f in fs))

    def test_listed_port_no_finding(self):
        fs = exposure.run(Context(targets=[T("svc", "h:9100")],
                                  baseline={"allowed_ports": ["9100"]}))
        self.assertEqual(fs, [])

    def test_no_port_baseline_skips_unlisted_check(self):
        """没给端口白名单就不做「基线外」判定（避免全报），但仍查高危端口。"""
        fs = exposure.run(Context(targets=[T("svc", "h:8080")], baseline={}))
        self.assertFalse(any(f.id.startswith("exposure:unlisted:") for f in fs))

    def test_dead_host_targets_skipped(self):
        """指向失联主机的目标由 drift 报，exposure 跳过，避免同根因重复计数。"""
        fs = exposure.run(Context(
            targets=[T("svc", "192.168.1.159:8082"), T("svc", "h:9200")],
            baseline={"known_dead_hosts": ["192.168.1.159"], "allowed_ports": ["9100"]},
        ))
        self.assertFalse(any("192.168.1.159" in f.id for f in fs))
        # 9200 是高危端口且主机不在失联名单，仍应报 P0（白名单不影响高危判定）
        self.assertTrue(any(f.id == "exposure:highrisk:h:9200" and f.severity == "P0" for f in fs))

    def test_non_numeric_instance_ignored(self):
        fs = exposure.run(Context(targets=[T("prom", "localhost:9090")], baseline={}))
        self.assertEqual(fs, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
