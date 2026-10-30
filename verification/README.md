# 第 4 资产：Agent 外部验证工具箱

> **一句话价值**：Agent 产出怎么被证明是对的？——场景用例 + 评分脚本 + CI 门禁。
> 三个原型共用这一套验证基础设施，体系 > 孤例。

## 组成（随原型开发滚动充实，W4 起已有实体内容）

```
verification/
├── scenarios/      # 场景用例：infra-checkup-cases.md（黄金用例 + 构造快照 + 真机对照）
├── scoring/        # 评分脚本：各原型自带评分器（infra-checkup 的 scoring/run_score.py），
│                   #           元验证「评分器对已知答案必须 100% 判对」
├── gates/          # CI 门禁：.github/workflows/ci.yml（三原型 job + 密钥扫描 + 评分器元验证）
└── redteam/        # known-defects.md：公开「已知缺陷清单」，可信度 > 完美度
```

已落地：
- `scenarios/infra-checkup-cases.md` —— 原型 3 的黄金用例、分级规则快照、真机对照
- `redteam/known-defects.md` —— 三个原型的已知缺陷与未做事项，随每次真机核对更新
- CI gate —— `ci.yml` 覆盖 self-healing-agent / model-router / infra-checkup 三个 job

原型 3 的真机外部裁判结果：160 的 32 个抓取目标人工逐条核对，drift 维度
precision=1.000 / recall=1.000 / 漏报 0 / 误报 0（详见
`prototypes/infra-checkup/scoring/manual-review-160.md`，含「这个 1.0 是怎么改出来的」）。

## 设计原则

- **绝不让模型自己评判自己**：单元测试、benchmark、截图比对、真实环境对照，外部裁判说了算
- **真实故障注入**：在靶场主动制造故障，测 Agent 的定位与恢复，不只测「顺风局」
- **可信度 > 完美度**：已知缺陷公开挂在 redteam/，漏报误报数据不藏
- 门禁脚本接入 CI 后，任何 README 里的性能声明都有自动核对

## 验证方式（元验证）

验证体系自身也要被验证：评分脚本对已知答案的用例必须 100% 判对，
否则先修评分器再评原型。
