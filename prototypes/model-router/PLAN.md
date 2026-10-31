# model-router — W3 改造计划（原型 2）

> 前身：`D:\AI\tts-router`（多引擎 TTS 路由器，OpenAI 兼容 /v1/audio/speech，内嵌于本目录，原始 README 见 README-tts-router-origin.md，commit f93f7a9）。
> 原目录保留不动；本目录是它的 LLM 化演进现场。

## 为什么要从 TTS 路由改造，而不是从零写

TTS 路由器已经把「多渠道 LLM 网关」最难的部分写完了，而且被真实使用验证过：

| 已有能力（tts-router 现成） | 对 LLM 网关的意义 |
|---|---|
| EngineRegistry 优先级选路 | 渠道路由（价格/能力/健康度） |
| BrokenEngine 熔断 + 冷却 | 渠道 503/402 自动摘除（MikuAPI 现挂、DeepSeek 402，天然测试样本） |
| UsageLedger 计量 + 配额 | 成本账：tokens × 单价，文章②的核心数据源 |
| ApiKeyAuthFilter | 网关鉴权 |
| AudioCache | 语义缓存（LLM 侧改造点：按 prompt hash，谨慎处理） |
| Web 控制台 | 渠道健康/用量看板 |

改造的本质：**把「引擎」的抽象从音频合成泛化为一切 OpenAI 兼容调用**。

## 改造清单（按依赖顺序）

1. **抽象换轴**：`TtsEngine/HttpEngine/SpeechCommand` → `LlmChannel/ChannelClient/ChatRequest`；`/v1/audio/speech` → `/v1/chat/completions`（透传 + 流式 SSE）
2. **渠道配置**：`config/providers.yaml`（仓库已有模板）接进 EngineRegistry；价格数据（输入/输出 tokens 单价）成为选路的一等公民
3. **路由策略**：默认「能力匹配 → 价格最低 → 健康优先」；显式 `model` 字段可锁定渠道；熔断语义复用（连续 N 次 5xx/超时 → 冷却）
4. **成本记账**：UsageLedger 扩展 tokens 维度，JSONL 落盘（与 self-healing-agent 的 journal 同格式纪律），按渠道/模型/天聚合
5. **JDK 对齐**：21 → 25（与作品集统一），Boot 3.5.16 保留
6. **真实故障演练**：Miku 503 摘除/恢复、DeepSeek 402 降级、Ark 兜底，测切换耗时与成功率——数据进文章②
7. **CI**：ci.yml 加 model-router job（JDK 25，本目录无 SNAPSHOT 依赖，应零障碍）

## 不做的事

- 不做多租户、不做饭桶计费——这是个人实验室，账记给自己看
- 不做流式转协议适配（各渠道都是 OpenAI 兼容，SSE 透传即可）
- 前身方向上的 TTS 功能（ffmpeg 转码、WAV 拼接、长文切分）保留代码但不演进，README 说明冻结

## 验收门（与 README 发布门一致）

- [x] 测试全通过 + CI 绿（47 个测试）
- [x] 成本数据有出处：`data/usage.jsonl` 原件 + `ops/usage-report.py` 聚合脚本（按天/渠道/模型，
      未知成本单独计数不计入总额）
- [x] 真实故障演练：降级（miku 404/502、deepseek 402、ark 兜底）+ **恢复**（`ChannelRecoveryTest`
      走真实 HTTP 验证 开闸 → 冷却 → 半开 → 回主档）
- [x] 文章②初稿（`docs/article-2-model-router.md`，由留档素材整理：渠道验证记录 / 账本 / commit 链；
      内嵌 3 处【口述待补】槽位——反 AI 味纪律的兑现方式改为「不编、留槽、发布前补全」，发布前逐条清零）
