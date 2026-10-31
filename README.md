# ai-ops-portfolio — 个人 AI Ops 实验室

[![ci](https://github.com/guozhengit/ai-ops-portfolio/actions/workflows/ci.yml/badge.svg)](https://github.com/guozhengit/ai-ops-portfolio/actions/workflows/ci.yml)

> **一句话价值**：别人演示 AI 能做什么，我证明 AI 在生产里怎么不出事、怎么算得清账。
>
> 三个原型 + 一套验证体系，统一挂在同一套基础设施（统一网关 / 验证 / 计量）上，
> 覆盖 Agent 工程化的三个核心问题：

| 项目 | 解决的问题 | 状态 |
| --- | --- | --- |
| [prototypes/self-healing-agent](prototypes/self-healing-agent) | 怎么让 Agent 碰真实生产数据并可靠地干活 | ✅ W2 功能完成（盲测 6/6 + 红队误报场，文章①已重写待发布） |
| [prototypes/model-router](prototypes/model-router) | 怎么在多模型环境下控制成本与可用性 | ✅ W3 功能完成（验收门 4 过 4：文章②初稿已成，留 3 处口述槽位发布前补全） |
| [prototypes/infra-checkup](prototypes/infra-checkup) | 怎么把人的检查方法论固化成 Agent 流程 | ✅ W4 功能完成（真机核对 precision/recall=1.0，文章③初稿已成） |
| [verification/](verification) | Agent 产出怎么被外部证明是对的（第 4 资产） | 🔨 建设中（三原型场景用例 + 评分器 + 红队清单已落地，CI 门禁已上线） |

**面试串场**：我做了三个原型，分别覆盖 Agent 工程的三个核心问题——可靠性（自愈 Agent）、
成本与可用性（路由网关）、方法论固化（体检 Agent）；第四个资产（外部验证工具箱）回答
「你怎么知道它们没在瞎说」。

---

## 仓库结构

```
ai-ops-portfolio/
├── prototypes/          # 三个原型（每个可独立运行、独立发布文章）
│   ├── self-healing-agent/   # 原型 1：Prometheus 告警 → Agent 诊断 → 沙箱验证 → 人工审批
│   ├── model-router/         # 原型 2：多模型路由网关（渠道四元组 / 熔断分级 / 成本账本；GLM、DashScope 与本地兜底档未接，见 known-defects）
│   └── infra-checkup/        # 原型 3：端口审计/凭据扫描/配置漂移的 Agent 化体检
├── verification/        # 第 4 资产：Agent 外部验证工具箱（场景用例 + 红队缺陷清单 + CI 门禁；评分脚本随原型 3 自带）
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

- **换实例前先查端口占用**（model-router）：Bash 后台任务停止只杀外壳，Java 子进程继续占端口 + 锁住 H2 文件，新实例"秒起"其实是老实例在应答，换错实例的信号是"启动太快"和"渠道凭空消失"
- **测试替身会掩盖装配问题**（self-healing-agent）：`@ConditionalOnMissingBean` 挂在普通 `@Component` 上条件求值顺序不定，主路径起不来而集成测试因 `@Primary` 替身一直绿 —— 真实进程冒烟必须做
- **未知价目记 0 等于把"不知道"写成"免费"**（model-router）：第三方中转没有公开价目，账本记 `costUsd: null` + `costKnown: false`，比不记更糟
- **`.gitignore` 规则会跨层级生效**（infra-checkup）：`.env.*` 静默忽略了 `fixtures/.env.prod` 靶场（本地绿 CI 红）；`reports/local/` 没有前导 `**/` 只匹配仓库根 —— 交付前必须复查暂存区实际文件清单
- **账本位置以原型目录为准**（self-healing-agent）：仓库根 `data/` 下有同名 0 字节空壳 jsonl（来源待查），真实账本在 `prototypes/self-healing-agent/data/`（approvals 7 行 / incidents 13 行）——引用前先核对行数
- **内网采集要绕过环境代理**（infra-checkup）：urllib 被透明代理劫持，外网模型调用反被代理栈卡死（90s+ 超时），最后改成内网 `ProxyHandler({})` + 外网 curl 主通道

## 已知的坑与未达成项

失败的部分和成功的一样需要写清楚，详见 [docs/article-4-summary.md](docs/article-4-summary.md) 的「没做成的部分」：

- MTTD ≤5min 未达成（健康路径基线 8.6min，降级盲测场 9–15min；同场景串行→并行 A/B：8m02s→6m42s）
- 动作提取器无兜底，"白名单自动止血"未在全自动链路跑通
- GLM / DashScope 仍是占位符 key；本地 LM Studio 兜底档位未实现
- 原型一 webhook 仍指向本机，服务未上服务器常驻
- exposure 检查器未做真实全端口扫描，结论范围仅限 Prometheus 抓取清单

---

*配套文档：6 周行动表 + 定位文档（野心版 v2.0）+ [迭代方向 V2](docs/roadmap-v2.md)（模型不可内化物：授权边界 / 成本账本 / 外部验证）。本仓库是活文档，每周日复盘滚动修订。*
