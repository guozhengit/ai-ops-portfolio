#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""credscan 黄金用例：已知答案必须对 100%，且脱敏后绝不出现任何凭据原文。"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from infra_checkup.model import Context
from infra_checkup.checkers import credscan

FIXTURES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures")


class TestCredscanGolden(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fs = credscan.run(Context(scan_roots=[FIXTURES]))
        cls.by_id = {f.id: f for f in cls.fs}

    def test_golden_ids_exact(self):
        """该报的一条不能少，不该报的一条不能多。id 集合精确相等。

        fixtures/.env.prod 被根 .gitignore 用 `!` 显式放行（否则它会被静默忽略，
        CI checkout 后没有这个文件→测试红）。因此它是「未受 gitignore 保护的
        生产密钥文件」样本，正好验证 P0 判定 + 「未忽略」P0 检测。
        """
        expected = {
            "credscan:.env.prod:2:DB_PASSWORD",
            "credscan:app.yml:6:password",
            "credscan:service.py:12:LEGACY_TOKEN",
            "credscan:service.py:13:FALLBACK_SECRET",
            "credscan:service.py:20:jdbc",
            "credscan:.env.prod:8:LEGACY_ADMIN_PASSWORD",
            "credscan:app.yml:9:password",
            "credscan:gitignore:.env.prod",
        }
        self.assertEqual(set(self.by_id), expected)

    def test_severity_mapping(self):
        want = {
            # 靶场 .env.prod 是「未忽略的生产密钥文件」→ P0（模拟真实泄露风险）
            "credscan:.env.prod:2:DB_PASSWORD": "P0",
            "credscan:app.yml:6:password": "P0",
            "credscan:service.py:12:LEGACY_TOKEN": "P0",
            "credscan:service.py:13:FALLBACK_SECRET": "P0",
            "credscan:service.py:20:jdbc": "P0",
            "credscan:.env.prod:8:LEGACY_ADMIN_PASSWORD": "P2",
            "credscan:app.yml:9:password": "P2",
            "credscan:gitignore:.env.prod": "P0",
        }
        for fid, sev in want.items():
            self.assertEqual(self.by_id[fid].severity, sev, fid)

    def test_env_p0_only_when_not_ignored(self):
        """同一份 .env 内容：被 gitignore 覆盖→P2，未覆盖→P0。分级取决于保护状态。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, ".env.prod"), "w", encoding="utf-8") as fh:
                fh.write("DB_PASSWORD=SuperSecretProd123456\n")
            fs = credscan.run(Context(scan_roots=[d]))
            by = {f.id: f for f in fs}
            hit = [f for k, f in by.items() if "DB_PASSWORD" in k]
            self.assertEqual(len(hit), 1)
            self.assertEqual(hit[0].severity, "P0")
            self.assertFalse(hit[0].meta["env_ignored"])

    def test_placeholders_not_reported(self):
        """占位符 / 环境变量 / 空值 / 压缩文件一律不报（重点回归「带引号占位符」误报）。"""
        ids = set(self.by_id)
        self.assertNotIn("credscan:service.py:16:EXAMPLE_API_KEY", ids)
        self.assertTrue(not any("INTERNAL_KEY" in i or "API_TOKEN" in i for i in ids))
        self.assertTrue(not any("bundle.min.js" in i for i in ids))

    def test_no_plaintext_leak(self):
        """脱敏纪律：所有 evidence 里不得出现任何凭据原文，只能有 ***(len=N)。"""
        secrets = [
            "SuperSecretProd123456", "ProdDbPassw0rd!2026",
            "sk-live-9f8e7d6c5b4a3210fedcba9876543210",
            "hardcoded_fallback_secret_value", "EmbeddedJdbcPass123",
            "OldPasswordShouldBeP2", "your_api_key_here",
        ]
        blob = "\n".join(f.evidence for f in self.fs)
        for s in secrets:
            self.assertNotIn(s, blob, "证据里泄露了明文: %s" % s)

    def test_jdbc_mask_keeps_protocol(self):
        ev = self.by_id["credscan:service.py:20:jdbc"].evidence
        self.assertIn("jdbc:mysql://", ev)
        self.assertIn(":***@", ev)
        self.assertNotIn("EmbeddedJdbcPass123", ev)

    def test_fixture_env_prod_flagged_unignored(self):
        """靶场 .env.prod 被 !例外放行（可入库），应正确报「未忽略」P0。"""
        hits = [f for f in self.fs if f.id == "credscan:gitignore:.env.prod"]
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0].severity, "P0")

    def test_real_repo_env_example_not_false_positive(self):
        """真实仓库的 .env.example 是入库模板，不该报「未忽略」P0（真机踩过的误报）。"""
        real_root = os.path.abspath(os.path.join(FIXTURES, "..", ".."))
        fs = credscan.run(Context(scan_roots=[real_root]))
        self.assertFalse(any("env.example" in f.id and f.id.startswith("credscan:gitignore:")
                             for f in fs))


class TestCredscanOrphan(unittest.TestCase):
    def test_env_without_gitignore_reports_p0(self):
        """隔离目录里造一个无 .gitignore 的 .env，应报 gitignore P0。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, ".env"), "w", encoding="utf-8") as fh:
                fh.write("PASSWORD=OrphanSecretValue123\n")
            fs = credscan.run(Context(scan_roots=[d]))
            self.assertTrue(any(f.id.startswith("credscan:gitignore:") and f.severity == "P0"
                                for f in fs))


class TestRealRepoFalsePositives(unittest.TestCase):
    """真机扫描 ai-ops-portfolio 暴露的三类误报，逐条钉死回归。"""

    def test_env_var_name_reference_not_reported(self):
        """api-key-env: MIKU_API_KEY 值是环境变量名，不是凭据值。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "application.yml")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("channels:\n  - id: miku\n    api-key-env: MIKU_API_KEY\n"
                         "    api-key-env: ARK_API_KEY\n")
            fs = credscan.run(Context(scan_roots=[d]))
            self.assertEqual(fs, [], "环境变量名引用被误报: %s" % [f.id for f in fs])

    def test_third_party_site_packages_skipped(self):
        """site-packages 里的 TOKEN_* 常量不该被扫出来。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            sp = os.path.join(d, "site-packages", "jinja2")
            os.makedirs(sp)
            with open(os.path.join(sp, "lexer.py"), "w", encoding="utf-8") as fh:
                fh.write("TOKEN_BLOCK_BEGIN = '{%'\nTOKEN_VARIABLE_BEGIN = '{{'\n")
            fs = credscan.run(Context(scan_roots=[d]))
            self.assertEqual(fs, [], "第三方库被误扫: %s" % [f.id for f in fs])

    def test_placeholder_changeme_variants(self):
        """sk-change-me / lm-studio 这类占位符变体不报。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "app.yml")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("api_key: sk-change-me\npassword: change-me\nsecret: your_secret_here\n"
                         "local_key: lm-studio\n")
            fs = credscan.run(Context(scan_roots=[d]))
            self.assertEqual(fs, [], "占位符被误报: %s" % [f.id for f in fs])

    def test_env_template_not_flagged_as_unignored(self):
        """.env.example 等入库模板：未忽略是设计意图，不报 gitignore P0。"""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            for name in (".env.example", ".env.sample", ".env.template"):
                with open(os.path.join(d, name), "w", encoding="utf-8") as fh:
                    fh.write("API_KEY=your_key_here\n")
            fs = credscan.run(Context(scan_roots=[d]))
            self.assertFalse(any(f.id.startswith("credscan:gitignore:") for f in fs),
                             "模板文件被误报未忽略: %s" % [f.id for f in fs])


if __name__ == "__main__":
    unittest.main(verbosity=2)
