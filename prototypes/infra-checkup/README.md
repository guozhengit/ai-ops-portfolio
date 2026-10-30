# 原型 3：基础设施体检 Agent

一条命令跑完，出一份只读的体检报告。它扫作品集仓库里的明文凭据，读 160 上
Prometheus 的抓取健康，把发现按 P0/P1/P2 分级，报告发出后还能就某一条用自然语言
追问「具体怎么排查」。整体是 Python 标准库写的，没装任何第三方依赖，CI 里也能跑。

这个原型想证明的不是「Agent 也会扫」，是**把人做巡检的方法论固化下来之后，
漏报和误报可以被量化、被回归、被改进**。所以有个刻意的设计：检查器只认一个
`Context` 进、`Finding` 出，谁都不许改被检查的对象——跟原型 1 那句
「agent proposes, human disposes」是同一条边界。

## 现在能干什么

- 一条命令 `python cli.py run --config config/checkup.example.json` 出报告 + JSONL 证据链，
  有 P0/P1 就退出码 1，能直接当 CI/cron 的门禁。
- 三个可插拔检查器：`credscan`（明文凭据，脱敏）、`drift`（配置漂移/抓取健康）、
  `exposure`（端口暴露面）。注册表是显式的，少注册一个会直接报错，不会静默少跑。
- `drift` 会**自己推断主机级故障**：同一个 IP 上多个目标同时 down，不用人先告诉它
  哪台机器死了。这次 160 上就是它自己把「192.168.1.159 上 20 个目标全瞎」聚成一条 P1。
- 追问链 `python cli.py explain <finding-id>` 接方舟真模型，关掉深度思考后十几秒出
  一份按执行顺序的排查步骤；没 key 或调用失败会如实降级，不编答案；还带反注入自检。
- 采集能力缺什么就在报告顶部写什么（degraded），绝不静默跳过假装查过了。

## 这次真机跑出来的结果

对着 160 的 32 个抓取目标，加扫整个作品集仓库，最终报 **P0 0 条、P1 5 条、P2 5 条**。
5 条 P1 里，一条是 192.168.1.159 主机失联（本机 DHCP 早就从 .159 变成了 .16，
监控配置却还指着旧 IP，20 个目标全瞎）；另外四条是 clickhouse / kafka / qdrant /
rocketmq 的抓取目标——`lastError` 全是 Docker 内嵌 DNS 的 `no such host`，
说明服务发现里配了根本不存在的容器名，这属于配置漂移。5 条 P2 是 `.env` 里那些
真实渠道 key，但文件已被 gitignore 覆盖，是合规的本地密钥存放，只是提示确认别误提交。

人工逐条核对这 32 个目标后，drift 维度的 precision 和 recall 都是 1.000，漏报 0、
误报 0。但这个 1.0 **不是一次跑出来的**——见下面「踩坑」。

## 快速开始

```bash
cd prototypes/infra-checkup

# 真机体检（连 160 + 扫作品集仓库）
python cli.py run --config config/checkup.example.json

# 追问某条怎么修（需要先 set -a; . ../../.env; set +a）
python cli.py explain drift:hostdown:192.168.1.159 --config config/checkup.example.json

# 元验证：评分器对已知答案必须 100% 判对
python cli.py verify-scorer

# 全量单测（45 例，纯标准库）
python -m unittest discover -s tests

# 真机外部裁判评分
python scoring/run_score.py            # 连 160
python scoring/run_score.py --offline  # 用留档的 findings.jsonl
```

配置里所有相对路径都以本目录（cli.py 所在）为基准。自动产物落在
`reports/local/`（已被 gitignore 忽略），要留档的真机报告手动拷到 `reports/`。
`fixtures/` 是评测靶场，故意埋了假凭据，真机跑时会自动跳过，只用于黄金用例单测。

## 踩坑记录（这次真踩出来的，不是想出来的）

**一开始把 159 的 20 个目标逐条报，产出 24 条 P1 噪音**，而且判定依赖我手工填进
`known_dead_hosts` 的基线——等于工具只是把人已经知道的死 IP 复述一遍。这不是
「固化方法论」，是抄答案。改成主机级聚合 + 无基线自推断之后，收敛成 1 条 P1，
而且即使不填基线，工具照样能自己判出来（`self_inferred=True`）。

**clickhouse 那四个一开始被我降成 P2「需人工确认」**。人工核对时盯着 `lastError`
看才发现全是 Docker DNS 的 `no such host`——这不是「服务可能临时挂了」，是配置里
写了根本不存在的容器名，是确定性的配置错误。补了 DNS 判定才升成 P1。工具第一版
把确定的事说成不确定，也是一种不诚实。

**拿真实仓库扫 credscan，初版报了 22 条，其中 17 条是误报**。逐条归因才理清：
`.env` 里的真 key 被当成泄露（其实它被 gitignore 覆盖，放真 key 正是设计意图）；
`api-key-env: MIKU_API_KEY` 被当成明文（值是环境变量名，不是凭据值）；
`.venv-chaos/site-packages` 里 jinja2 的 `TOKEN_*` 常量被扫出来；`sk-change-me`
因为前面有 `sk-` 前缀躲过了锚定的占位符正则；`.env.example` 被 `!.env.example`
故意放行且已 tracked，却被报成「未忽略，有泄露风险」。五类修完，真实仓库从 22 条
假告警收敛到 5 条 P2，误报清零。这段过程写在 `scoring/manual-review-160.md`。

**gitignore 又吞文件了**，跟原型 2 的 `ApiKeyAuthFilter.java` 一模一样的根因。
`fixtures/.env.prod` 被 `.env.*` 静默忽略，本地有、CI checkout 后没有 → 本地绿 CI 红；
而 `reports/local/` 这条规则因为没写前导 `**/`，只匹配仓库根那一层，
`prototypes/infra-checkup/reports/local/` 根本没被忽略，报告文件差点入库。
两处都在 `.gitignore` 里修好了。

**代理方向内外网是反的**。内网 160 采集必须绕开环境代理（`ProxyHandler({})`），
否则拿到代理劫持的假响应；外网方舟调用恰恰要走代理，`urllib` 在这个环境走代理会卡到
超时，而 curl 走同一代理 1.8 秒就 200。最后追问链主通道改成 curl 子进程，key 通过
`-K` 临时配置文件传、绝不进 argv，urllib 只作兜底。

**推理模型会吃光正文**。豆包 seed 是推理模型，不关深度思考会先耗十几秒到几分钟想，
`max_tokens` 给小了正文直接被 reasoning 吃光（原型 2 踩过 64 被截断）。加
`thinking:{"type":"disabled"}` 后十几秒出完整回答。

## 还没做的（挂红队清单，不藏）

- exposure 只依据 Prometheus 抓取清单判定，SSH 免密不通，没做真实全端口扫描。
  所以「0 条高危端口」只说明抓取清单里没有，不能说 160 上没暴露。每条高危 finding
  都标了 `verified_exposure=False`。
- `expected_targets` 基线为空，本轮不做目标清单增删比对，报告顶部有 degraded 提示。
- 主机级自推断要求「同 IP 且 ≥2 个目标同时 down」，单目标主机失联会漏成 P2。
- 评分口径只覆盖 drift 维度；credscan 靠 45 个黄金单测保证，真仓库那几条 P2 是
  提示性发现，没有独立二值真值，不进 precision/recall。

完整缺陷清单见 `verification/redteam/known-defects.md`。

## 验证到什么程度

- 45 个单测全绿，纯标准库，`-W error::ResourceWarning` 下无未关闭文件句柄
- 元验证：评分器对已知答案 100% 判对
- 真机：160 的 32 个目标 + 作品集仓库实扫，人工逐条核对，drift 维度 precision/recall=1.000
- 追问链：真方舟 key 成功路径 + 无 key/超时降级路径 + 反注入拦截，三条都实测过
- CI：新增 infra-checkup job（编译/单测/元验证/评分链路冒烟），并修了 paths 过滤器

设计决策与纠偏过程见 `DESIGN.md`，人工核对记录见 `scoring/manual-review-160.md`，
真机报告留档在 `reports/checkup-160-20261030.md`。
