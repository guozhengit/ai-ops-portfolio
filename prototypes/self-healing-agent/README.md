# 原型 1：自愈型运维 Agent（旗舰项目，面试主打）

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

## 快速开始

（W2 开发期补齐）

## 验证方式（外部裁判，不许自评）

预设 3 个故障场景，Agent 正确定位 ≥2 个，输出带指标截图的完整报告；
修复动作在隔离环境执行并回显结果；危险动作必须人工审批（agent proposes, human disposes）。

## 刻意不做的

自动执行修复（未审批）、多集群支持、UI —— 记入 roadmap。

## 踩坑记录

（滚动补充）
