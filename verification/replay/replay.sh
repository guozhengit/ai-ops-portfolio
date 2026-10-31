#!/usr/bin/env bash
# 证据重放门禁（V2 期2 C2，借鉴 EverOS「Markdown=真相，索引可重建」的存储分层）
#
# 原理：原始记录（append-only jsonl / 黄金答案 / 留档 findings）= 真相层；
#       评分结果与聚合账本 = 派生层。派生层必须能从真相层逐位重建，否则证据不可信。
# 门禁：重建到临时目录（不碰入库原件）→ 剔除非确定性字段后逐位比对 → 通过才刷新 manifest。
# 任何一步失败即退出非零，CI 直接红。
#
# 注意：新增证据需在下方 manifest 清单显式登记——登记即声明它纳入重放范围。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

FINDINGS="prototypes/infra-checkup/reports/findings-160-20261030.jsonl"
USAGE="prototypes/model-router/data/usage.jsonl"

echo "[replay] 1/4 评分重放：从留档 findings 重建 score-result"
python prototypes/infra-checkup/scoring/run_score.py \
  --offline-file "$FINDINGS" --out "$SCRATCH/score-result-160.json" >/dev/null

# TZ 必须钉住：usage-report 的 byDay 用 astimezone() 本地时区分桶（UTC+8），
# CI 是 UTC，不同时区重放会造出假差异。
echo "[replay] 2/4 账本重放：从 usage.jsonl 重建 usage-report（TZ=Asia/Shanghai）"
TZ=Asia/Shanghai python ops/usage-report.py \
  --path "$USAGE" --json --json-out "$SCRATCH/usage-report.json" >/dev/null

echo "[replay] 3/4 归一化比对（剔除非确定性字段 source/generatedAt）"
python - "$SCRATCH" <<'PY'
import json, os, sys
scratch = sys.argv[1]
NONDET = {"source", "generatedAt"}
PAIRS = [
    ("score-result-160",
     os.path.join(scratch, "score-result-160.json"),
     "prototypes/infra-checkup/scoring/score-result-160.json"),
    ("usage-report",
     os.path.join(scratch, "usage-report.json"),
     "prototypes/model-router/data/usage-report.json"),
]
failed = False
for name, rebuilt, committed in PAIRS:
    a = {k: v for k, v in json.load(open(rebuilt, encoding="utf-8")).items() if k not in NONDET}
    b = {k: v for k, v in json.load(open(committed, encoding="utf-8")).items() if k not in NONDET}
    if a != b:
        failed = True
        print(f"[replay] FAIL {name}: 重放结果与入库产物不一致")
        for k in sorted(set(a) | set(b)):
            if a.get(k) != b.get(k):
                print(f"  字段 {k}:\n    重放={str(a.get(k))[:220]}\n    入库={str(b.get(k))[:220]}")
    else:
        print(f"[replay] OK   {name}: 逐位一致（仅 source/generatedAt 豁免）")
if failed:
    sys.exit(1)
PY

echo "[replay] 4/4 刷新证据清单 manifest"
mkdir -p verification/evidence
sha256sum \
  prototypes/self-healing-agent/data/incidents.jsonl \
  prototypes/self-healing-agent/data/approvals.jsonl \
  prototypes/self-healing-agent/reports/incident-20261031T073301.637357400Z-e2e1002.md \
  prototypes/self-healing-agent/docs/action-classes.md \
  prototypes/model-router/data/usage.jsonl \
  prototypes/model-router/data/usage-report.json \
  prototypes/model-router/data/usage-report-20261029.json \
  prototypes/model-router/data/usage-report.run-spec.json \
  prototypes/infra-checkup/reports/findings-160-20261030.jsonl \
  prototypes/infra-checkup/scoring/groundtruth-160.json \
  prototypes/infra-checkup/scoring/score-result-160.json \
  prototypes/infra-checkup/scoring/score-result-160.run-spec.json \
  verification/run-spec.template.json \
  verification/scenarios/self-healing-groundtruth.json \
  > verification/evidence/manifest.sha256
echo "[replay] 全部通过：重放逐位一致，manifest 已刷新（verification/evidence/manifest.sha256）"
