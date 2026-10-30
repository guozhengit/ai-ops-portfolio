# 场景用例集 · infra-checkup（原型 3）

 golden 用例与真机对照分开，两者作用不同：前者证明「判定逻辑对」，后者证明「真实环境里对得上」。

## A. 黄金用例（离线，CI 可跑）

靶场目录 `prototypes/infra-checkup/fixtures/`，每个文件都是已知答案：

| 文件 | 埋的东西 | 期望 | 防的回归 |
| --- | --- | --- | --- |
| `.env.prod` | `DB_PASSWORD=SuperSecretProd123456`（真值形状） | P0 | 明文凭据检出 + 脱敏 |
| `.env.prod` | `# LEGACY_ADMIN_PASSWORD=...`（整行注释） | P2 | 注释不该升 P0（上游 strip_comment 缺陷） |
| `.env.prod` | `API_TOKEN=your_api_token_here`、`INTERNAL_KEY=${VAULT_KEY}` | 不报 | 占位符/环境变量不算违规 |
| `app.yml` | `password: ProdDbPassw0rd!2026` | P0 | 配置文件从严 |
| `app.yml` | `# password: ...`（注释） | P2 | 同上 |
| `service.py` | `LEGACY_TOKEN="sk-live-..."`（带引号长字面量） | P0 | 代码硬编码 |
| `service.py` | `EXAMPLE_API_KEY="your_api_key_here"` | 不报 | 带引号占位符（上游漏判点） |
| `service.py` | `jdbc:mysql://user:EmbeddedJdbcPass123@...` | P0 | JDBC 内嵌口令 + 脱敏保留协议 |
| `bundle.min.js` | 超 500 字符压缩行含 password/apiKey | 不报 | minified 跳过，避免误报洪水 |

补充用例（临时目录构造，见 `tests/test_credscan.py`）：
- 无 `.gitignore` 的 `.env` → 报「未忽略」P0
- `api-key-env: MIKU_API_KEY` → 不报（值是环境变量名）
- `site-packages` 里的 `TOKEN_*` → 不报（第三方库跳过）
- `sk-change-me` / `lm-studio` → 不报（占位符变体）
- `.env.example` 等入库模板 → 不报「未忽略」

## B. 构造快照用例（drift / exposure 分级规则）

见 `tests/test_drift.py`、`tests/test_exposure.py`。关键几条：
- 无基线时，同 IP 4 个目标同时 down → 自推断主机级 P1（`self_inferred=True`）
- 命中 `known_dead_hosts` → 措辞变「已确认」
- 单目标 IP down / 容器名 down → P2，不武断升主机级
- `lastError` 含 Docker DNS `no such host` → 配置漂移 P1
- job 多目标全 down → job 整体失明 P1；有 up 有 down → 部分 down P2
- 高危端口（6379/9200…）→ P0 且标记 `verified_exposure=False`
- 指向失联主机的目标由 drift 报，exposure 跳过，不重复计数

## C. 真机对照（160，人工裁判）

- 数据源：`http://192.168.1.160:9091/api/v1/targets`（32 个 activeTargets，只读）
- 人工逐条核对记录：`prototypes/infra-checkup/scoring/manual-review-160.md`
- 黄金答案：`prototypes/infra-checkup/scoring/groundtruth-160.json`
- 评分结果：`prototypes/infra-checkup/scoring/score-result-160.json`（precision=1.000 / recall=1.000 / 漏报 0 / 误报 0，drift 维度）
- 复跑：`python prototypes/infra-checkup/scoring/run_score.py`（连 160）或 `--offline`
- 真机报告留档：`prototypes/infra-checkup/reports/checkup-160-20261030.md`

## 元验证（评分器自己也要被验证）

`python prototypes/infra-checkup/cli.py verify-scorer` —— 评分器对已知答案必须
100% 判对，否则先修评分器再评原型。CI 里这一步是独立门禁。
