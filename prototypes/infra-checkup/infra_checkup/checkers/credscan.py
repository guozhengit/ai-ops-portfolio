#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""明文凭据检查器。

判定逻辑移植自 agentscope-learning/tools/secret_scan.py，四条原样继承：
1. 硬编码脱敏——命中只报 键名 + ***(len=N)，绝不打印原文，否则「自查报告」本身成泄密源。
2. 占位符不算违规——${ENV} / your_xxx / 空串 属合规写法，不报。
3. 分层判定——配置文件（凭据真正存放处）从严，代码只报带引号长字面量，避免误报。
4. 只做只读扫描，不修改任何文件。

文件名刻意叫 credscan 而非 credential_scan：根 .gitignore 有 `**/*credential*`，
原型 2 的 ApiKeyAuthFilter.java 已被这类规则静默吞过一次（本地绿、CI 红）。
"""

from __future__ import annotations

import os
import re
from typing import Any, Dict, List, Tuple

from ..model import Context, Finding

CONFIG_EXT = {".yml", ".yaml", ".properties", ".env", ".xml", ".ini",
              ".conf", ".config", ".toml", ".json", ".sql"}
CODE_EXT = {".java", ".js", ".ts", ".tsx", ".vue", ".py", ".go", ".sh", ".bat", ".gradle"}
SCAN_EXT = CONFIG_EXT | CODE_EXT

SKIP_DIRS = {
    ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode",
    ".mvn", "__pycache__", "venv", ".venv", "logs", "log", ".workbuddy",
    ".next", "coverage", ".gradle", "static", "assets", "reports", "data",
    # 评测靶场目录：真机扫整个仓库时跳过，避免故意埋的假凭据混进真实发现
    "fixtures", "demo", "samples",
    # 第三方依赖与虚拟环境：全是库源码（jinja2 的 TOKEN_* 常量等），扫了只会制造误报。
    # 真机踩过：ops/chaos-demo/.venv-chaos/Lib/site-packages 一次贡献 11 条假 P0。
    "site-packages", ".venv-chaos", "Lib", "lib64",
}

CRED_KEY = re.compile(
    r"(?i)(pass(?:word|wd|phrase)?|secret|token|api[_-]?key|access[_-]?key|secret[_-]?key"
    r"|private[_-]?key|credential|pwd|密码|密钥|凭证)"
)
ASSIGN = re.compile(
    r"(?i)([\w.\-\"\[\]]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
    r"|secret[_-]?key|private[_-]?key|credential)[\w.\-\"\[\]]*)\s*[:=]\s*(\S.*)"
)
JDBC = re.compile(r"(?i)jdbc:[a-z0-9]+://[^\s:@/]+:([^\s@]+)@")
PLACEHOLDER = re.compile(
    r"(?i)^(\$\{.*|%.*%|\{\{.*|null|none|true|false|\d+|"
    r"\*+|x+|your[_-]?.*|change[_-]?me|changeme|example|placeholder|"
    r"<.*>|\"\s*\"|'\s*'|todo|fixme)$"
)
IGNORE_KEY = re.compile(r"(?i)^(\"?tokens\"?|.*_MIN_TOKENS|.*_RUN_TOKENS|.*_TOKEN_CONST|TOKEN_[A-Z_]+)$")

# 「值是一个环境变量名」而非凭据值：全大写 + 下划线 + 数字的标识符形态。
# 真机踩过：application.yml 的 `api-key-env: MIKU_API_KEY` 被当成明文凭据报 P0，
# 其实它只是告诉程序「去哪个环境变量取 key」。排在 IGNORE_KEY 之后作为第二道过滤。
ENV_VAR_NAME = re.compile(r"^[A-Z][A-Z0-9_]{2,}$")


# 值里含这些占位片段的一律视为示例值。真凭据不会包含 change-me / your_ /
# placeholder / example / dummy / sample 这类词，所以这样放宽不会误杀真 token。
# 真机踩过：application-prod.yml 的 `sk-change-me` 因前面有 sk- 前缀，
# 躲过了以 change 开头的锚定正则，被误报 P0。
PLACEHOLDER_FRAGMENT = re.compile(
    r"(?i)(change[_-]?me|your[_-]|placeholder|example|dummy|sample|redacted|"
    r"xxxx|test[_-]?key|<[^>]+>)"
)

# 本地服务无密钥档位的固定占位值（LM Studio 只要个非空串即可，不是凭据）。
LOCAL_PLACEHOLDER = {"lm-studio", "ollama", "none", "no-key", "local", "n/a"}

# .env 模板类文件：本就该入库（被 !.env.example 例外放行），
# 「未被 gitignore 忽略」是设计意图而非风险，不能报「未忽略」P0。
ENV_TEMPLATE_NAMES = {".env.example", ".env.sample", ".env.template", ".env.dist"}


def _is_placeholder(val: str) -> bool:
    v = val.strip()
    if not v or "${" in v or "{{" in v or v.startswith("%") or v.startswith("<"):
        return True
    if bool(PLACEHOLDER.match(v)):
        return True
    if PLACEHOLDER_FRAGMENT.search(v):
        return True
    if v.lower() in LOCAL_PLACEHOLDER:
        return True
    # 纯大写下划线标识符 = 环境变量名引用，不是凭据值（api-key-env: MIKU_API_KEY 之类）
    if ENV_VAR_NAME.match(v):
        return True
    return False


def _strip_comment(val: str) -> Tuple[str, bool]:
    v = val.strip()
    commented = v.lstrip().startswith(("#", "//", "*", "<!--"))
    for sep in (" #", "\t#", " //", " <!--"):
        idx = v.find(sep)
        if idx > 0:
            v = v[:idx].rstrip()
    return v.strip().strip(","), commented


def _unquote(val: str) -> Tuple[str, bool]:
    v = val.strip().strip(",")
    if len(v) >= 2 and v[0] in "\"'" and v[-1] == v[0]:
        return v[1:-1], True
    return v, False


def _mask(val: str) -> str:
    return "***(len=%d)" % len(val.strip())


def _is_minified(lines: List[str]) -> bool:
    return any(len(l) > 500 for l in lines[:50])


def _classify(path: str, commented: bool, env_ignored: bool = False) -> str:
    if commented:
        return "P2"
    base = os.path.basename(path).lower()
    is_env = base == ".env" or base.startswith(".env.")
    if is_env:
        # 已被 .gitignore 覆盖的 .env*：在这里放真实 key 是设计意图（本地密钥文件、永不入库），
        # 不是「明文入库风险」。降 P2 保留可见性，措辞在调用处单独说明；
        # 未被忽略的 .env* 仍是 P0（真机踩过：混淆这两种会造成一堆假 P0）。
        return "P2" if env_ignored else "P0"
    if re.search(r"(?i)(prod|prd|release|online)", path):
        return "P0"
    if re.search(r"(?i)(localhost|127\.0\.0\.1|/test/|src/test/|test_)", path):
        return "P1"
    return "P0"


def _scan_file(path: str, ext: str, root: str, env_ignored: bool = False) -> List[Finding]:
    out: List[Finding] = []
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
            lines = fh.readlines()
    except OSError:
        return out
    if _is_minified(lines):
        return out
    rel = os.path.relpath(path, root).replace("\\", "/")
    is_config = ext in CONFIG_EXT

    for i, raw in enumerate(lines, 1):
        line = raw.rstrip("\n")
        if not line.strip():
            continue
        # 整行注释判定：上游 secret_scan.py 的 strip_comment 只看等号右边的值，
        # 检测不到 "# password=xxx" 这种整行注释，会把注释残留误升成 P0。
        # 这里在行级补判，注释行一律走 P2。
        line_comment = line.lstrip().startswith(("#", "//", "/*", "*", "<!--"))

        # jdbc 内嵌口令
        if JDBC.search(line):
            def _mask_jdbc(m):
                # 只替换 @ 前的口令段，保留协议与主机，避免 evidence 事实变形。
                whole = m.group(0)
                at = whole.rfind("@")
                head = whole[:at]
                last_colon = head.rfind(":")
                return head[:last_colon] + ":***" + whole[at:]
            masked = JDBC.sub(_mask_jdbc, line)
            out.append(Finding(
                id="credscan:%s:%d:jdbc" % (rel, i),
                checker="credscan", severity=("P2" if line_comment else "P0"),
                category="credential",
                title="JDBC 连接串内嵌明文口令",
                evidence="%s:%d  %s" % (rel, i, masked.strip()[:150]),
                why=("注释行，凭据残留降级 P2" if line_comment
                     else "连接串里带口令，任何能读该文件的人都能直连数据库"),
                suggestion="口令改为环境变量/配置中心注入，连接串只保留占位符",
                meta={"key": "jdbc-url-password", "commented": line_comment},
            ))
            continue

        m = ASSIGN.search(line)
        if not m:
            continue
        key, rawval = m.group(1), m.group(2)
        if not CRED_KEY.search(key) or IGNORE_KEY.match(key.strip()):
            continue
        val, commented = _strip_comment(rawval)
        if _is_placeholder(val):
            continue
        literal, quoted = _unquote(val)
        # 上游 secret_scan.py 只在「去引号前」判占位符，带引号的占位符字面量
        # 如 "your_api_key_here" 因引号挡在 your 前面而漏判，会被误报成 P0。
        # 去引号后再判一次，堵住这个类误报。
        if _is_placeholder(literal):
            continue

        commented = commented or line_comment
        base_lower = os.path.basename(rel).lower()
        is_env_file = base_lower == ".env" or base_lower.startswith(".env.")
        if is_config:
            if len(literal) < 3:
                continue
            sev = _classify(rel, commented, env_ignored=env_ignored)
        else:
            if not quoted or len(literal) < 8:
                continue
            sev = _classify(rel, commented, env_ignored=env_ignored)
            if sev == "P0" and len(literal) < 16:
                sev = "P1"

        if is_env_file and env_ignored and not commented:
            why = "该文件已被 .gitignore 覆盖：在此存放真实 key 属合规的本地密钥文件设计，"
            why += "非明文入库风险；仅提示确认它确实未被 git 跟踪"
            suggestion = "确认 git check-ignore 命中、且从未被误提交进历史即可，无需移动"
            title = "本地密钥文件（已被 gitignore 忽略）：%s" % key.strip()
        else:
            why = ("配置文件中的非占位符值视为明文入库" if is_config
                   else "代码中的带引号长字面量，疑似硬编码凭据")
            if commented:
                why += "（该行是注释，降级 P2）"
            suggestion = "移到环境变量或密钥管理服务，提交前用 grep 复查"
            title = "疑似明文凭据：%s" % key.strip()

        masked_line = line.replace(val.split("#")[0].strip(), "***").strip()[:150]
        out.append(Finding(
            id="credscan:%s:%d:%s" % (rel, i, re.sub(r"\W", "_", key.strip())[:24]),
            checker="credscan", severity=sev, category="credential",
            title=title,
            evidence="%s:%d  %s = %s  |  %s" % (rel, i, key.strip(), _mask(literal), masked_line),
            why=why,
            suggestion=suggestion,
            meta={"masked_value": _mask(literal), "line": i, "file": rel,
                  "commented": commented, "env_ignored": bool(is_env_file and env_ignored)},
        ))
    return out


def _gitignore_rules_for(path: str):
    """收集 path 自身及其所有上级目录里 .gitignore 的合并文本，直到仓库根（.git 所在层）。

    从 secret_scan.py 继承来的缺陷：它只看扫描根目录的 .gitignore，
    但 fixtures 这类子目录其实被仓库根的 .env.* 规则覆盖。只看本级会把
    「已被忽略」误判成「未忽略」→ 假 P0。这里向上合并，并对 `!` 例外做基本处理。
    """
    cur = os.path.abspath(path)
    texts = []
    while True:
        gi = os.path.join(cur, ".gitignore")
        if os.path.isfile(gi):
            try:
                with open(gi, "r", encoding="utf-8", errors="ignore") as fh:
                    texts.append(fh.read())
            except OSError:
                pass
        if os.path.isdir(os.path.join(cur, ".git")) or os.path.dirname(cur) == cur:
            break
        cur = os.path.dirname(cur)
    merged = "\n".join(texts)
    ignores_env = ".env" in merged or ".env.*" in merged or ".env*" in merged
    # 只对 .env* 这类简单模式判断；复杂的 ! 例外不在此展开，命中不确定就保守不报 P0
    return ignores_env


def _env_file_ignored(path: str, fallback: bool) -> bool:
    """精确判断某个 .env* 文件是否被 git 忽略。

    优先用 git check-ignore（唯一权威口径，能正确处理 !.env.example 这类例外）：
    退出码 0 = 被忽略，1 = 未被忽略，其它 = git 不可用/非仓库 → 回退到文本规则。
    真机踩过：.env.example 被 !例外放行（本就可入库），若一律按「.env* 被忽略」处理，
    会把可入库模板里的占位符也误降级，口径不准。
    """
    import subprocess
    try:
        repo = os.path.dirname(os.path.abspath(path))
        proc = subprocess.run(
            ["git", "check-ignore", "-q", os.path.basename(path)],
            cwd=repo, capture_output=True, timeout=5,
        )
        if proc.returncode == 0:
            return True
        if proc.returncode == 1:
            return False
    except (OSError, subprocess.SubprocessError):
        pass
    return fallback


def _gitignore_issues(root: str, force_unignored: bool = False) -> List[Finding]:
    issues: List[Finding] = []
    fallback_ignored = False if force_unignored else _gitignore_rules_for(root)
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn == ".env" or fn.startswith(".env."):
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root).replace("\\", "/")
                # .env.example / .sample / .template / .dist 是入库模板，未忽略是设计意图，
                # 不报「未忽略」P0（真机踩过：.env.example 被 !例外放行且已 tracked，
                # 曾误报成 risk）。真凭据检查仍会扫它的内容。
                if fn.lower() in ENV_TEMPLATE_NAMES:
                    continue
                # 靶场强制判「未忽略」，与 _scan_file 口径一致且不依赖 git 可用性。
                ignored = False if force_unignored else _env_file_ignored(full, fallback_ignored)
                if not ignored:
                    issues.append(Finding(
                        id="credscan:gitignore:%s" % rel,
                        checker="credscan", severity="P0", category="credential",
                        title="存在 .env 文件但 .gitignore 未忽略",
                        evidence="%s（git check-ignore 判定为未被忽略）" % rel,
                        why="此类文件一旦 git add 就会把真实凭据提交进仓库历史",
                        suggestion="在 .gitignore 加 .env 规则，并核对是否已误提交",
                        meta={"file": rel},
                    ))
    return issues


def run(ctx: Context) -> List[Finding]:
    findings: List[Finding] = []
    for root in ctx.scan_roots:
        if not os.path.isdir(root):
            continue
        root_abs = os.path.abspath(root)
        fallback_ignored = _gitignore_rules_for(root)
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in filenames:
                is_env = fn == ".env" or fn.startswith(".env.")
                ext = os.path.splitext(fn)[1].lower()
                # .env.prod 的 splitext 扩展名是 .prod，不在白名单；必须显式认 .env* 家族，
                # 否则这类文件会被静默跳过（测试全绿但漏扫）。
                if ext not in SCAN_EXT and not is_env:
                    continue
                full = os.path.join(dirpath, fn)
                scan_ext = ".env" if is_env else (ext if ext else ".env")
                # 靶场 fixtures 固定模拟「未受保护的泄露样本」，分级不依赖 git 命令是否可用
                # （CI 环境 git 可用性与本地可能不同，不能让黄金用例的预期漂移）。
                is_fixture = _is_fixture_root(root_abs)
                env_ignored = False if is_fixture else (
                    _env_file_ignored(full, fallback_ignored) if is_env else False)
                for f in _scan_file(full, scan_ext, root, env_ignored=env_ignored):
                    f.meta["scan_root"] = root_abs
                    f.meta["demo_fixture"] = _is_fixture_root(root_abs)
                    findings.append(f)
        for f in _gitignore_issues(root, force_unignored=_is_fixture_root(root_abs)):
            f.meta["scan_root"] = root_abs
            f.meta["demo_fixture"] = _is_fixture_root(root_abs)
            findings.append(f)
    return findings


def _is_fixture_root(root_abs: str) -> bool:
    """标记评测靶场来源。报告据此把「故意埋的假数据」与「真机发现」分区，避免误导读者。"""
    norm = root_abs.replace("\\", "/").lower().rstrip("/")
    tail = norm.rsplit("/", 1)[-1]
    return tail in {"fixtures", "demo", "samples"} or norm.endswith("/fixtures")
