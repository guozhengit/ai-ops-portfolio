# ai-ops-portfolio — 个人 AI Ops 实验室

[![ci](https://github.com/guozhengit/ai-ops-portfolio/actions/workflows/ci.yml/badge.svg)](https://github.com/guozhengit/ai-ops-portfolio/actions/workflows/ci.yml)

> **一句话价值**：别人演示 AI 能做什么，我证明 AI 在生产里怎么不出事、怎么算得清账。
>
> 三个原型 + 一套验证体系，统一挂在同一套基础设施（统一网关 / 验证 / 计量）上，
> 覆盖 Agent 工程化的三个核心问题：

| 项目 | 解决的问题 | 状态 |
| --- | --- | --- |
| [prototypes/self-healing-agent](prototypes/self-healing-agent) | 怎么让 Agent 碰真实生产数据并可靠地干活 | ✅ W2 功能完成（文章①已重写待发布） |
| [prototypes/model-router](prototypes/model-router) | 怎么在多模型环境下控制成本与可用性 | ✅ W3 功能完成（验收门 4 过 3，剩文章②） |
| [prototypes/infra-checkup](prototypes/infra-checkup) | 怎么把人的检查方法论固化成 Agent 流程 | ✅ W4 功能完成（文章③初稿已成，真机核对 precision/recall=1.0） |
| [verification/](verification) | Agent 产出怎么被外部证明是对的（第 4 资产） | 🔨 建设中（场景用例/评分/红队已落地，门禁接 CI） |

**面试串场**：我做了三个原型，分别覆盖 Agent 工程的三个核心问题——可靠性（自愈 Agent）、
成本与可用性（路由网关）、方法论固化（体检 Agent）；第四个资产（外部验证工具箱）回答
「你怎么知道它们没在瞎说」。

---

## 仓库结构

```
ai-ops-portfolio/
├── prototypes/          # 三个原型（每个可独立运行、独立发布文章）
│   ├── self-healing-agent/   # 原型 1：Prometheus 告警 → Agent 诊断 → 沙箱验证 → 人工审批
│   ├── model-router/         # 原型 2：6 家云端 + LM Studio 本地兜底的多模型路由网关
│   └── infra-checkup/        # 原型 3：端口审计/凭据扫描/配置漂移的 Agent 化体检
├── verification/        # 第 4 资产：Agent 外部验证工具箱（场景用例 + 评分脚本 + CI 门禁）
├── docs/                # 发布文章、复盘记录、失败案例
├── ops/                 # 靶场脚本与部署物料（故障注入 demo 服务等）
├── config/              # 供应商路由配置模板（价格表 / 能力分 / 降级策略）
├── .env.example         # Key 配置模板（占位符，真实 Key 填在 .env，永不入库）
└── .gitignore           # Key 治理：一切含密文件默认拒之门外
```

## 快速开始

```bash
git clone <repo-url> && cd ai-ops-portfolio
cp .env.example .env        # 填入你自己的 Key（此文件已被 .gitignore 覆盖）
```

各原型的启动方式见各自 README。

## 验证方式（发布门禁三关）

任何原型/文章发布前，必须过三关：

1. **测试全通过** —— 单测/集成测试绿，CI 可复现
2. **数据有出处** —— 性能、成本、准确率数据附测量方法与原始记录
3. **红队缺陷清单已更新** —— 公开「已知缺陷」，可信度 > 完美度

## 踩坑记录

- （随开发滚动补充，失败复盘与成功展示同等重要）

---

*配套文档：6 周行动表 + 定位文档（野心版 v2.0）。本仓库是活文档，每周日复盘滚动修订。*
