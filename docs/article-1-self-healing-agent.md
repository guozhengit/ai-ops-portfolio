# 文章①：我给故障靶场配了个「AI 值班员」——从告警到带证据链的诊断报告，人只做审批

> 定位：掘金/公众号技术短文。配套仓库：github.com/guozhengit/ai-ops-portfolio（prototypes/self-healing-agent）
> 写作纪律：数据全部可复现，测量方法随表给出；AI 搞不定的地方如实写，不藏。

## 一句话

一个 Flask 故障注入靶场 + Prometheus/Alertmanager + 基于 AgentScope harness Team 的诊断 Agent：故障发生 → 告警自动触发 → Agent 团队取证诊断 → 生成带证据链的 Markdown 报告 → 白名单内低风险动作自动止血，其余进人工审批队列。全程无人工，实测告警到出报告 6 分 42 秒。

## 为什么做这个

值班 SRE 的一天：告警响了 → 凭经验猜 → 登几台机器敲命令 → 拉群问人 → 写报告。其中「取证」和「写报告」占掉大半时间，而这两件事恰恰是大模型最擅长的：查指标、读原文、交叉验证、按模板输出。

所以边界划得很清楚：**Agent 只做只读取证和方案建议；一切变更动作要么在白名单内（混沌开关复位），要么等人批。** 这是「能编排 AI 干活」和「让 AI 乱来」的分界线。

## 架构（4 个角色 + 1 条红线）

- 靶场 chaos-demo：5 个真实故障场景（真 OOM、真磁盘膨胀、真连接钉死），幂等开关 `POST /fault/<scene>/on|off`
- Prometheus 按容器名抓取 chaos_ 指标，6 条告警规则自主求值
- Alertmanager 按 `Chaos.*` 路由，Bearer 认证推 webhook
- Agent（AgentScope harness Team）：lead 拆派任务 → metrics-analyst（PromQL 只读）/ log-analyst（指标原文）/ remedy-planner（结构化建议）→ lead 汇总出报告
- **红线**：修复动作白名单 `chaos:off:<scene>`（5 个场景硬编码）+ 风险分级。低风险自动执行并留 JSONL 审计，其余等人批——Agent 手里永远没有 shell。

## 盲测结果（D12，告警只给症状不给答案）

| 场景 | 注入 | Agent 判定 | 结果 |
|---|---|---|---|
| 内存泄漏 | memory-leak | RSS=泄漏持有 690MiB、GC 不可回收型、排除其余 4 场景 | ✅ |
| 慢查询 | slow-sql | 136 条查询 2–10s 双峰、均值 34.6ms 正常 | ✅ |
| 磁盘满 | disk-full | 虚拟盘 100.83% 满、真实盘 7.73% 正常，边界清晰 | ✅ |
| 连接池耗尽 | pool-exhaustion | 应用池 10/10 钉死 vs DB 6/150 健康，**主动否决 KILL 会话** | ✅ |
| 注册抖动 | nacos-flap | 时间线分析排除慢查询长尾因果 | ✅ |
| **红队误报** | **无** | 判「告警不成立/已自愈」，回溯历史注入窗口，**禁止预防性清盘** | ✅ |

红队那场是我最满意的：我发了一条假磁盘告警，它没有顺着 P1 的压力编造「磁盘真的满了」，而是查了实时 ALERTS、真实卷水位、toggle 计数，最后把 3 小时前那次 10 分 38 秒的注入窗口精确还原，结论是「告警不成立，禁止预防性删除」。**反幻觉不是靠提示词求出来的，是靠「先查证再下结论」的工具编排逼出来的。**

## 三个踩坑（比成功更有信息量）

1. **MikuAPI 在测试窗口持续 503**——反而成全了重试/降级链路的真实验证：单调用指数退避重试 3 次 → 整场自动切 fallback 模型重跑 → 报告头自动打降级标记。6 场盲测全部由降级路径命中，主模型挂掉只变慢、不断供。
2. **Dockerfile 漏 COPY 一个文件**——本地源码目录跑一直没暴露，容器化部署第一天就撞上。教训：镜像要按「冷启动」标准验收，不是「我机器上能跑」。
3. **Alertmanager 0.34 不支持 webhook 自定义 header**——`http_config.headers` 直接 yaml unmarshal 报错，换标准的 `authorization` 块 + 服务端双通道认证。

## 还没做好的（如实说）

- **MTTD 6m42s，离 5 分钟目标还差一段**。worker 并行化已把串行 3 回合压成 1 回合（8m02s→6m42s，同场景同测量方法），剩下的大头是 lead 的任务拆派和最终报告合成两段串行 LLM 调用——下一步试并行拆派和流式汇总。
- 观测盲区：靶场没有应用日志接口，Agent 只能如实标注「证据缺口」而不是编。补日志通道后，「容量不足 vs 连接泄漏」这种二选一才能真正定性。
- 诊断模型走降级路径时的报告可信度需要人工复核——降级标记是自动打的，但「复核」目前还是人来。

## 测量方法（可复现）

- MTTD = incidents.jsonl 里 webhook receivedAt → 报告文件 mtime。前后对比用同一场景（pool-exhaustion）、同一链路、同一模型配置，仅差 worker 串行/并行。
- 盲测协议：每场只给 `alertname + 症状 summary`，不告诉 Agent 是哪个场景；判定标准为报告结论是否正确定位注入场景及证据链完整。
- 全部报告原件、审计 JSONL、验证脚本在仓库 `prototypes/self-healing-agent/reports/` 与 `verification/`。

---

## 附：2 分钟演示视频脚本（供录制，OBS/terminal 录屏）

| 时间 | 画面 | 旁白要点 |
|---|---|---|
| 0:00-0:15 | 终端 `curl POST /fault/pool-exhaustion/on`，chaos-demo UI 面板连接池灯变红 | 「我往测试环境注入一个连接池耗尽故障，不告诉任何人——包括那个 AI 值班员」 |
| 0:15-0:40 | Prometheus UI：ChaosPoolExhausted pending → firing；Alertmanager UI 出现告警 | 「Prometheus 自己发现它，1 分钟规则窗口后 firing，Alertmanager 自动推给 Agent」 |
| 0:40-1:30 | agent 日志滚动：lead 拆派 → 三个 worker 并行取证 → lead 汇总；快进 | 「三个分析员并行取证：查 PromQL、拉指标原文、出修复方案。lead 汇总成报告」 |
| 1:30-1:50 | 打开生成的 Markdown 报告，滚过证据链和修复建议表 | 「6 分 42 秒后，报告在这里：结论、证据链原文引用、每条建议带风险和审批要求」 |
| 1:50-2:00 | 审批队列 UI：一条白名单动作已自动执行（开关复位），一条待人工审批 | 「白名单内的止血动作它自己干了，审计留痕；其它它只建议，不碰。这就是我给它的权限边界」 |

---

状态：初稿 v1（2026-10-29）。发布前待办：MikuAPI 恢复后补主模型路径 MTTD 数据；Hub 镜像补推后加一行 pull 命令。
