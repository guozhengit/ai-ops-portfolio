# V2 分期设计：三期落地（原型与流程设计）

> 状态：执行版（2026-10-31 定稿，随周日复盘滚动修订）
> 上游：[roadmap-v2.md](roadmap-v2.md)（方向）→ 本文（分期 + 各期原型/流程设计 + 验收门）
> 事实基线：2026-10-31 对三个原型与 verification/ 的代码侦察，本文所有设计均锚定现存结构，不凭空画 schema。

## 0. 分期总览

| 期 | 时间 | 主题 | 一句话 | 验收标志 |
| --- | --- | --- | --- | --- |
| **期 1 · 地基期** | W5–W6 | 纪律件 + 发布债 | 证据纪律先于实验：归因卡、动作分级表、发布三关 | 文章 ×3 发布；run_spec 全量回填；分级表 v1 |
| **期 2 · 实验期** | W7–W8 | 三个实验全启动 | 信任标定 / tier 矩阵 / 预算熔断 / 证据重放，四线并行 | 四个实验各有一条可复核数据链 |
| **期 3 · 数据期** | W9+ | key 融入 + 数据成型 | 三代际数据补全，文章 5 选题按数据完整性决策 | 三代际误拦率表 或 重放链路，二选一成文 |

依赖关系：期 2 的信任标定实验依赖期 1 的动作分级表（判断"该拦不该拦"需要制度基准）与提取器兜底关账；期 3 依赖 key 外部到位。其余项互相独立，可并行。

---

## 期 1 · 地基期（W5–W6）

**目标**：不写新机制，把三件事立住——数字可归因（B3）、动作有分级（A1 的制度层）、发布债清零。低代码周，主要是文档与格式件。

### D1.1 测量归因卡 run_spec v1（B3）

**载体**：`verification/run-spec.template.json`（已落）+ 各测量产物旁 `<产物名>.run-spec.json`。

**格式**（五段）：`specVersion / subject / artifact / generatedAt / model{provider, modelName, baseUrl, keyFingerprint, fallback} / config{configVersion, routerConfig} / environment{host, notes} / measurement{method, rawRecords[], knownCaveats[]}`。

**关键纪律**：
- `keyFingerprint` 只存 sha256 前 8 位，永不落明文（对齐密钥治理）
- `rawRecords` 必须指向 append-only 原始记录（usage.jsonl / approvals.jsonl / incidents.jsonl），不指向派生聚合
- `knownCaveats` 强制非空——每张卡至少声明一条口径限制（对齐"不证明什么"纪律）

**回填范围**（首批 4 张卡）：
| 产物 | 已有归因信息 | 缺口 |
| --- | --- | --- |
| `prototypes/model-router/data/usage-report.json` | 渠道/模型在行级有 | 无 configVersion、无 caveat |
| `prototypes/self-healing-agent/reports/*d12*.md` | 报告头有"诊断模型"+降级标记 | 分散在 md 头，无机器可读卡 |
| `prototypes/infra-checkup/scoring/score-result-160.json` | source 字段 | 无模型/环境段（本轮不依赖模型，如实标 null） |
| `prototypes/self-healing-agent/docs/d12-blindtest.md` 计时表 | 口径在 article-1 散文里 | 口径收进卡内 |

### D1.2 动作分级表 v1（A1 制度层）

**载体**：`prototypes/self-healing-agent/docs/action-classes.md`，人工维护的制度文件，先于代码。

**Schema**（每个动作类一行）：`动作类 / 可逆性（可逆|难逆|不可逆）/ 影响面（单实例|跨实例|跨系统）/ 爆炸半径（回滚成本一句话）/ 当前闸门（auto | approve | forbidden）/ 毕业条件（升 auto 需要什么数据）`。

**首版内容**（锚定现状：白名单内只有 `chaos:off:<scene>` 五个场景，其余 command=null 进人工队列"批准也仅记录"）：

| 动作类 | 可逆性 | 影响面 | 爆炸半径 | 当前闸门 | 毕业条件 |
| --- | --- | --- | --- | --- | --- |
| `chaos:off:*`（解除注入） | 可逆（可重新 on） | 单实例（chaos-demo） | 近零 | auto（whitelisted && risk=low） | 已毕业（本表基准点） |
| `chaos:on:*`（注入故障） | 可逆 | 单实例 | 近零 | approve | 靶场操作，不毕业 |
| 服务重启类（未来） | 难逆 | 单实例 | 会话丢失 | approve | ≥3 代际 0 事故 + 副作用清单 |
| 数据写入/删除类（未来） | 不可逆 | 跨实例 | 不可回滚 | **forbidden** | 无——制度线，永不毕业 |

**最后一条是 V2 的锚**：不可逆类写死 forbidden，是制度线不是能力线，模型再可靠也不移动（roadmap 主线 A 的论证落地）。

**提取器 schema 扩展**（reversibility/blast_radius 字段进 `{description, risk, executable_command}`）**故意推迟到期 2**，与提取器兜底关账一起动刀——提取器当前无兜底（只走 primary，D12 全场提取失败挂账中），一次改两处避免二次回归。

### D1.3 流程设计（期 1）

1. **发布三关流程**（文章 1/3/2）：测试全过（CI 绿）→ 数据有出处（回填 run_spec 卡）→ known-defects 已更新（C1 节 + 挂账复核）。三关顺序执行，卡在第二关 = run_spec 回填的验收压力。
2. **归因流程**：以后任何对外数字，先写 run_spec 卡再写文章表格；docs/README.md 写作纪律补一行。
3. **外部依赖启动**：GLM（智谱）/ DashScope 真 key 申请发出，W6 前完成（期 3 upgrade path 与三代际数据的前置，纯等待项）。

### 期 1 验收门

- [ ] 文章 1、3、2 全部发布（含口述槽位补全）
- [x] 4 张 run_spec 卡入库，模板进 verification/（2026-10-31 回填：usage-report / d12-reports / score-result-160 / d12-blindtest）
- [ ] `action-classes.md` v1 有"forbidden 永不毕业"行
- [ ] known-defects 有"不证明什么"节（**本设计落地时已顺手完成**）

---

## 期 2 · 实验期（W7–W8）

**目标**：四个实验各跑出一条可复核数据链。每个实验都有预注册的口径，跑完不许改口径。

### D2.1 信任标定实验台（A2，V2 招牌实验）

**要解决的现状缺口**（侦察结论）：原型 1 没有结构化真值（对比原型 3 有 groundtruth-160.json）；误拦/放行数据只天然积累在 `approvals.jsonl`（现存 7 行，真实自动执行仅 1 例）。

**前置代码活（第一项）**：提取器兜底——`proposeActions()` 的提取 agent 失败后走 fallback 链路（对齐诊断主链路 primary→fallback 整场重跑的既有形态）。关账 D12 遗留问题 #3，同时给提取器 schema 加 reversibility/blast_radius（见 D1.2）。

**真值补齐**：新建 `verification/scenarios/self-healing-groundtruth.json`（格式对齐原型 3 的 groundtruth-160.json 惯例：`golden_findings[{id, expected}]`）。真值来源是结构性的：chaos-demo 的 SCENES 字典即注入真值，每场盲测的正确 off 动作 = 注入场景本身（如 d12s1=pool-exhaustion → 正确动作 `chaos:off:pool-exhaustion`；d12rt 红队场 → 正确动作 = 空集）。6 场全部可先验标注。

**误拦三分类**（实验的核心口径，预注册后不许改）：
| 分类 | 定义 | 归因层 |
| --- | --- | --- |
| **提取漏** | 模型没提出正确动作（golden 里有、proposal 里没有） | 智能层——会随模型变强消失 |
| **制度误拦** | 提出了正确动作但没走 auto（risk 报高 / 白名单未放行，人工批后才执行或根本没提批） | 制度层——这是 A2 要量的 |
| **事故** | 提出了错误动作且被执行 | 制度层失效——一票否决项 |

这样设计的原因：把"智能层的不足"和"制度层的保守"分开，信任标定量的是后者随模型代际的移动。

**实验协议**：同一场景集（d12s1–s5 + rt）× 同一审批流 × 两代模型（方舟降级代际 vs 主模型恢复后代际）。每场记录 6 个数：提取成功数 / 提出动作数 / 自动执行数 / 制度误拦数 / 提取漏数 / 事故数。载体：`verification/scenarios/self-healing-cases.md` + 原始数据 append-only（对齐既有纪律）。

**沙箱缺口声明**：DESIGN.md D11 计划的 sandbox-verifier 未落地（现状是审批后真实执行 + 回显）。本实验**不新增执行面**（动作仍限于 chaos:off 白名单，靶场天然隔离），故不阻塞；sandbox-verifier 列期 2 可选项，做不完如实挂账。

### D2.2 渠道 tier 矩阵测试（B1）

**锚定现状**：`HttpChannelStatusTest` 已有"嵌入式 JDK HttpServer 真状态码 → 瞬态/确定性分类矩阵"的成熟打法；渠道配置在 `application.yml` 的 `router.channels`（id/base-url/api-key-env/models/priority/价目可空），失败语义在 `HttpChannel.classify()` 一行划线。

**设计**：三层 tier × 参数化测试（复用嵌入式 HttpServer，不走真外网，CI 稳定）：
- **tier1 单渠道**：只剩 1 个健康渠道，断言全流量归它、无降级噪音
- **tier2 主渠道故障**：503/404/402 混合注入，断言瞬态熔断走别名降级链、确定性错误只换道不熔断（复用 `classify()` 语义）、恢复后半开回主档（复用 `ChannelRecoveryTest` 形态）
- **tier3 全渠道健康**：断言 priority 排序 + 价目全知时账本 costKnown 全 true
- **upgrade path**：GLM/DashScope 以 401（key 无效）配置进矩阵 → 断言被启动校准禁用、路由不崩；key "补上"（同一测试改桩返回 200）→ 断言零配置改动纳入轮转

**CI 接入**：并入 model-router job（`mvn -B test` 已在跑，测试类加进去即可）。

### D2.3 预算熔断器（B2）

**范本复用**（侦察确认的最近结构）：TTS 侧 `Quota`（RouterProperties）+ `UsageLedger`（H2 usage_daily 按 key×日累计）+ `SpeechController` 请求前置拦截 429——三件套形态直接平移到 LLM 侧。

**挂点**：`ChatController` 成功路径 `journal.record(...)` 处（唯一逐笔拿到成本的位置，已回写 `X-Router-Cost-Usd`）。

**设计决策（关键，预注册）——unknown 成本的预算口径**：
- 账本层纪律不动：costUsd=null / costKnown=false 照旧（"不知道不写成免费"）
- 护栏层需要数字：null 没法护栏。**解法：上界估记**——unknown 成本按该渠道 aliases 链上最贵已知价目 × tokens 估算，仅用于预算判断，落账时标 `budgetEstimated=true`，与实测成本分列
- 这样"不记 0"的诚实纪律和"护栏要数字"的运行时需求各得其所，两边都不说谎

**机制**：日级预算 v1（对齐 usage_daily 形态）：`已用(含估算上界) + 本笔预估 > 预算` → 前置 429 `insufficient_budget` + 审计留痕 + 渠道级冷却（复用 CircuitBreaker 的 failures/openUntil 形态，独立的 BudgetGuard 持有）。任务级预算列期 3。

**演练**：QuotaIntegrationTest 同款——注入超限场景，断言 429、账本行、冷却恢复三件事。

### D2.4 证据重放（C2）

**现状雏形**（侦察确认）：`run_score.py --offline` 已能从 findings.jsonl 离线重建评分；`ops/usage-report.py --json` 已能从 usage.jsonl 完全重放 usage-report.json。重放不是新发明，是收拢成门禁。

**前置修复（半天量级）**：
1. findings.jsonl **0 条发现也落盘**（现状 0 条时无文件，重放会假阴性）——已落地（cli.py 无条件写）
2. ~~编码归一~~ **误诊更正**：字节级验证 checkup-160-20261030.md 本就是 UTF-8，此前「GBK」是 GBK 终端显示 UTF-8 的假象。教训：编码结论必须字节级验证，凭终端显示定编码会造出不存在的前置工作
3. md 报告（原型 1 reports/）**v1 不做重建**——设计决策：md 报告定性为"渲染产物"，证据锚定在其引用的 jsonl 记录 id 上；重建脚本列为期 3 可选

**重放门禁**：`verification/replay/replay.sh`：
```
删派生产物（score-result-160.json、usage-report.json）
→ 从原始记录重建（run_score --offline、usage-report.py --json）
→ diff 逐位比对
→ sha256sum 清单落 verification/evidence/manifest.sha256
```
通过 = 重放绿；进 CI 作 infra-checkup/model-router job 的附加 step。

### D2.5 流程设计（期 2）

1. **实验五步**：预注册口径（写进 cases 文件）→ 跑 → 人工复核 → 入账（run_spec 卡 + append-only 原始记录）→ 留痕（复盘记录）。口径跑完不改，改了就是新实验。
2. **清单进化空跑**（C3 首轮）：用当期最强模型对 infra-checkup 提出"该查什么新的"清单 → 人工评审拒纳理由 → 冻结决议留档。空跑不走 REGISTRY（规则拒绝自动发现是既有设计，保持）。

### 期 2 验收门

- [ ] 提取器兜底落地，主模型故障场提取不再全灭（**代码已落地**：fallback 重试 + schema 扩展 + 制度禁行守卫，2026-10-31；故障场复测待真实 primary 故障或实验窗口验证）
- [ ] 信任标定两代际 × 6 场，三分类数据表 + run_spec 卡（真值与协议已入库：`verification/scenarios/self-healing-groundtruth.json` + `self-healing-cases.md`；G2 单点 = e2e1002）
- [ ] tier 矩阵测试进 CI 全绿（含 upgrade path 桩版）
- [ ] 预算熔断演练通过（429 + 留痕 + 恢复）
- [x] 重放门禁 CI 绿，manifest.sha256 入库（2026-10-31 本地逐位一致 ×2；CI 随本提交生效；首跑即抓获 usage-report 快照滞后 19 行——门禁有效性实证）

---

## 期 3 · 数据期（W9+）

**目标**：外部 key 到位后的收尾期，数据成型 + 成文。

### D3.1 真渠道融入
- GLM/DashScope 真 key 配入 → tier 矩阵真机版复跑（桩版结论换真渠道复核）
- 信任标定加第三个代际（GLM），三代际误拦率表成型

### D3.2 文章 5 选题决策（预注册决策规则）
| 候选 | 成文条件 | 若数据未齐 |
| --- | --- | --- |
| 信任标定：审批流随模型代际怎么放松 | 三代际 × 6 场三分类数据完整 | 降级为两代际 + 边界讨论 |
| 证据防伪：删掉评分，重放能证明什么 | 重放门禁已绿（期 2 应已达成） | 现成，兜底选题 |

决策规则：W8 复盘时哪组数据先完整选哪个；重放选题永远保底。

### D3.3 清单进化第一轮实跑（C3）
期 2 空跑通过评审流程后，选 1 项新检查项走完全流程：提议 → 评审 → 实现（新 checker + REGISTRY + config + 测试 + groundtruth 口径扩展，五步件齐）→ 冻结进下一版。

### 期 3 验收门

- [ ] 三代际误拦率表（或明确的二代际边界结论）
- [ ] tier 真机版全绿
- [ ] 文章 5 发布
- [ ] 清单进化实跑 1 项入册

---

## 附：设计决策记录（预注册，改动需留痕）

| # | 决策 | 理由 | 备选被拒原因 |
| --- | --- | --- | --- |
| 1 | 动作分级表 v1 用制度文档，不用代码 schema | 分级是制度判断，先让人签字；代码扩展与兜底关账合并动刀 | 直接改提取器 schema：提取器无兜底，多动多险 |
| 2 | 误拦三分类（提取漏/制度误拦/事故） | 智能层与制度层分开量，否则模型变强会把两层混在一起"变好" | 单一"准确率"：分不开就不能回答"审批该不该放松" |
| 3 | unknown 成本护栏用上界估记，账本层保持 null | 护栏要数字，账本要诚实，两层各说各的真话 | 全按 0：违反"不记 0"死规矩；全拒付：unknown 渠道永远过不了预算 |
| 4 | md 报告 v1 不重建，证据锚定 jsonl | 重放的价值在"分数可复现"，md 是给人读的渲染层 | 全量 md 重建：成本高且 md 本身不进评分口径 |
| 5 | 信任标定实验不依赖沙箱落地 | 动作面限于 chaos:off 白名单，靶场即沙箱 | 等 sandbox-verifier：外部依赖拖死实验节奏 |
| 6 | tier 测试走嵌入式 HttpServer 桩，不上真外网 | CI 稳定可重复；真机另有 channel-report.json 核对留痕 | 真渠道进 CI：第三方故障不可控（known-defects 已挂账） |
