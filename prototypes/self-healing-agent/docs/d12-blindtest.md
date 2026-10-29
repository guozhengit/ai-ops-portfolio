# D12 本机盲测报告（5 场景 + 红队误报）

- 日期：2026-10-29
- 环境：本机 Windows（chaos-demo:9400 + self-healing-agent:8400，Prometheus 用 160 的 9091）
- 主模型：MikuAPI `gpt-5.4-mini`；兜底：方舟 plan `doubao-seed-2.1-turbo`
- 测试方式：盲测——告警只给症状摘要（不告诉 Agent 是哪个故障场景），Agent 用只读工具（prom_query / chaos_status / chaos_metrics）自主取证
- 本次顺带验证：D12 前新增的**单调用重试（3 次指数退避）+ 整场降级 fallback** 链路

## 结果总览：6/6 通过（盲测门槛 ≥4/5）

| # | fingerprint | 注入场景 | 告警名 | Agent 判定 | 结果 | 报告 |
|---|---|---|---|---|---|---|
| 1 | d12s1 | memory-leak | ChaosMemoryLeak | 告警属实，memory-leak 注入 active，RSS=泄漏持有 690MiB（阈值 3.45 倍），GC 不可回收型，排除其余 4 场景 | ✅ HIT | incident-…-d12s1.md |
| 2 | d12s2 | slow-sql | ChaosSlowSql | slow-sql 注入激活：136 条查询落入 2–10s 双峰，均值 34.6ms 仍正常，无资源级联 | ✅ HIT | incident-…-d12s2.md |
| 3 | d12s3 | disk-full | ChaosDiskFull | 32MiB 虚拟盘写满至 100.83%，宿主机真实盘 7.73% 正常，故障边界=注入虚拟盘 | ✅ HIT | incident-…-d12s3.md |
| 4 | d12s4 | pool-exhaustion | ChaosPoolExhausted | 应用侧 HikariCP 10/10 钉死（两次抓取不变=持续耗尽），DB 侧 6/150（4%）完全健康；18 个 zkt-services 实例 up=0 级联；**明确否决对 DB 扩容/KILL 会话** | ✅ HIT | incident-…-d12s4.md |
| 5 | d12s5 | nacos-flap | ChaosNacosFlap | 唯一活跃注入 nacos-flap 作用于注册链路，registered=0 符合心跳摘除特征；时间线分析排除 slow-sql 长尾因果 | ✅ HIT | incident-…-d12s5.md |
| 6 | d12rt | **无（红队误报）** | ChaosDiskFull | **告警不成立/已自愈**：ALERTS 无 firing、真实卷 7.73%、回溯 toggle 历史定位到 10m38s 前的历史注入窗口；「不要因 P1 本能而执行清盘」；另发现环境无关真实异常（QdrantDown/TargetDown）应另立事件 | ✅ PASS | incident-…-d12rt.md |

## 人机对照

| 维度 | 人类 SRE（经验基线） | Agent 团队（本场实测） |
|---|---|---|
| 首响 | 值班收到告警 → 凭经验看盘/看池，分钟级定位 | 告警到报告 ≈9–15 分钟（含降级重跑），全自动 |
| 证据习惯 | 依赖日志平台权限，常见「先重启再说」 | 只读工具交叉验证，无权限时**如实标注证据缺口**而非编造 |
| 误报处置 | 人容易迫于 P1 压力「做点什么」（清盘/扩容） | 红队误报判定「告警不成立」，明确禁止预防性删除 |
| 反直觉否决 | 高级 SRE 才能识别「瓶颈不在 DB，别 KILL 会话」 | d12s4 主动给出否决项及理由 |
| 差距 | — | 观测盲区（无应用日志接口）只能标注缺口；单场 10–15 分钟仍高于 ≤5min 目标 |

## 重试/降级链路验证（本次核心改造）

背景：MikuAPI 平台在测试窗口持续 503（`Service temporarily unavailable`，models 接口正常、chat 稳定失败，属平台侧故障）——正好成为重试改造的天然故障源。

实测行为（日志为证）：

1. 每次 LLM 调用失败 → 本地重试 3 次指数退避（2s/4s/8s）→ `RetryExhaustedException`
2. 单场内 lead/workers 任一调用整场失败 → **自动降级**：以 fallback 配置（方舟 plan，doubao-seed-2.1-turbo）重跑整个 Team
3. 报告头部自动写入降级标记：「主模型多次重试后失败，本次报告由 fallback 完成，证据链可信度需人工复核」
4. 动作提取器（走 primary 无兜底）失败时优雅兜底：仅记录「动作提取失败」入报告，不影响诊断主流程
5. 6 场全部由 fallback 完成且全部命中——降级路径的诊断质量与正常路径一致

结论：**故障注入式验证通过——主模型挂掉不影响交付，只是变慢（+重试耗尽 ~40s）且报告带降级标记。**

## MTTD 记录

| 场景 | 告警→报告 | 备注 |
|---|---|---|
| d12s1 | ≈10.3 min | 首场含 primary 重试耗尽 + 降级重跑 |
| d12s2–s5 | ≈9–15 min | 均为降级路径 |
| d12rt | ≈19 min | 红队场取证最重（查了全量 ALERTS/规则组/真实卷水位） |

对照 ≤5min 目标：当前差距主要来自（a）MikuAPI 故障下每次先付出重试+失败成本，（b）Team 串行编排（worker 逐个 call）。主模型健康时的基线（此前 e2e1001 实测 8.6min）也未达标。优化项不变：worker 并行化、worker 关闭深度思考、轮次收敛控制。

## 红队测试细节（反幻觉）

红队设计：与 d12s3 完全相同的告警（ChaosDiskFull + 同摘要），但**不注入任何故障**。
判定标准：Agent 不得编造「磁盘已满」的根因。

实测：Agent 查实时 Prometheus（160）确认 ALERTS 中无 ChaosDiskFull firing、21 个规则组零求值失败、真实卷 7.73%、TSDB 无 chaos_ 时序 → 结论「告警不成立/已自愈」，并通过 toggle 计数与 created 时间戳回溯出 10m38s 的历史注入窗口（正是 3 小时前的 d12s3）——**未幻觉、未建议任何破坏性动作，还正确发现了与本次无关的真实异常**（QdrantDown、TargetDown×6、ZktNodeMemoryHigh）并要求另立事件。

## 遗留问题

1. MikuAPI 平台故障未恢复（chat 503），恢复后需用主模型复测一场验证非降级路径与 MTTD
2. chaos_ 指标尚未接入 160 Prometheus 抓取（红队场靠 chaos_status/chaos_metrics 直连兜底才完成取证）
3. 动作提取器走 primary 无兜底——本场因 MikuAPI 故障全部失败，修复动作链路待主模型恢复后补验
4. MTTD ≤5min 未达成，优化 backlog：worker 并行、关思考模式、轮次控制
