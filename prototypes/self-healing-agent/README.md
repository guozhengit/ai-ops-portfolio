# 原型 1：自愈型运维 Agent（旗舰项目，面试主打）

> **D10 已完成端到端打通（2026-10-29）**：告警进 webhook → AgentScope Team 诊断
> （lead / metrics-analyst / log-analyst / remedy-planner，每 member 只读工具+独立权限上下文）
> → 带证据链的 Markdown 报告落盘 `reports/`。
> 首份真机报告：[reports/incident-…-e2e1001.md](reports/incident-20261029T073815.493948900Z-e2e1001.md)
> （诊断模型 doubao-seed-2.1-turbo，全程 8.6 分钟，报告含修复建议审批矩阵与反注入自检）。

> **设计方案已定稿（W1 产出）**：见 [DESIGN.md](DESIGN.md) —— /ambitious 四步：
> 改写后的任务 / 方法工具 / 野心目标 / 外部验证方式，含 5 故障场景与 W2 逐日节奏。

> **一句话价值**：接 Prometheus 告警 → Agent 自动诊断 → 沙箱内验证修复方案 →
> 产出带证据的报告，人只做审批。抬一层的目标不是「告警通知工具」，而是「让服务保持健康」。

## 架构图（占位，开发期补 mermaid/图片）

```
Alertmanager webhook ──> Agent 入口 ──> 诊断链路（MCP：查指标 / 查日志）
                                            │
                                            v
                              结构化方案（疑似原因/证据/建议动作/风险）
                                            │
                                            v
                              隔离容器执行验证（L21 链路）──> 人工审批闸门（HITL）
```

## 方案要点

- **底座**：直接建在 `D:\AI\agentscope-learning` 的 L20/L21 harness Team 框架上
  （权限引擎 / HITL / Docker 沙箱 / MCP 客户端 / 执行闸门 / 子代理路由，L1-L22 真机已跑通）
- 开发量 = 接 Prometheus + 写诊断场景，不是从零搭骨架
- 模型：GLM / Qwen / DeepSeek 任一；靶场：160 Prometheus + 故意不稳定的 demo 服务
  （随机 OOM 小应用）；hotel-booking / silver-guardian 为第二、三靶场
- 故障场景 4-5 个：内存泄漏、慢 SQL、磁盘打满 + 连接池打满、Nacos 注册异常

## 快速开始（D9：入口层已可运行）

> 运行环境：**JDK 25**（本机 `D:\jdk\jdk-25.0.5.0.3`，pom `java.version=25`）。
> JDK 25 下 Mockito 动态 attach 已通过 surefire `-XX:+EnableDynamicAgentLoading` 正规放行；
> 依赖里的 Guava `sun.misc.Unsafe` 弃用告警来自第三方，等上游修复，无碍。

```bash
mvn package && java -jar target/self-healing-agent-0.1.0-SNAPSHOT.jar \
    --server.port=8400 -Dselfhealing.webhook.token=$SELFHEALING_WEBHOOK_TOKEN
# 或：SELFHEALING_WEBHOOK_TOKEN=xxx java -jar ... --server.port=8400

curl -X POST http://localhost:8400/alerts/webhook \
     -H "X-Webhook-Token: $SELFHEALING_WEBHOOK_TOKEN" -H "Content-Type: application/json" \
     -d @alertmanager-sample.json
```

- 入口契约：令牌校验（常量时间比较）→ Alertmanager v2 payload 解析 →
  fingerprint+status 去重（60min TTL）→ 异步转派 → 证据 JSONL 落盘
  （`data/incidents.jsonl`，只追加不改写）
- 快速返回 2xx，重活全异步；原始报文整体留档，证据链不丢信息
- Alertmanager 侧接收器配置样例见 [deploy/alertmanager-receiver-sample.yml](deploy/alertmanager-receiver-sample.yml)
- 转派目前是占位实现（LoggingDispatcher），D10 由 AgentScope harness Team 接管
  （开关 `selfhealing.agent.enabled`）

## 验证状态（D9）

- 单测 7/7 绿（payload 解析 / dedupKey / 401 / 转派 / 去重 / 证据落盘）
- 真实进程端到端冒烟通过：401×2 → 200 accepted → 重复 deduplicated → journal 四条决定齐全

## 验证方式（外部裁判，不许自评）

预设 3 个故障场景，Agent 正确定位 ≥2 个，输出带指标截图的完整报告；
修复动作在隔离环境执行并回显结果；危险动作必须人工审批（agent proposes, human disposes）。

## 刻意不做的

自动执行修复（未审批）、多集群支持、UI —— 记入 roadmap。

## 踩坑记录

- **@ConditionalOnMissingBean 在普通 @Component 上不可靠**（2026-10-29 D9 冒烟发现）：
  LoggingDispatcher 用它做「有真实现就退位」，结果主路径启动时条件评估顺序不定，
  Dispatcher bean 缺失 → 应用起不来；而集成测试因 @Primary 测试替身一直是绿的。
  修复：改显式属性开关 `selfhealing.agent.enabled`。——教训：测试替身掩盖装配问题，
  真实进程冒烟必须做。
