# 原型 2：多模型路由网关（model-router）

一个 OpenAI 兼容的入口：`POST /v1/chat/completions` 进来，按「能力 → 优先级 → 单价 → 健康」选一个上游渠道发过去。渠道挂了自动降级，恢复了自动回来；每笔调用的成本落 JSONL 账本，**价目未知就记「未知」，不记 0**。

前身是 `D:\AI\tts-router`（多引擎 TTS 路由器），本目录是它 LLM 化的演进现场，原始 README 保留在 `README-tts-router-origin.md`。改造思路与验收门见 `PLAN.md`。

## 现在能干什么

| 能力 | 说明 |
| --- | --- |
| `/v1/chat/completions` | 非流式 + SSE 流式透传；响应头 `X-Router-Channel` 告诉你这发走了谁 |
| 5 个渠道 | `miku`（主档，`deepseek-v4.1-flash`）、`ark`（豆包）、`deepseek`、`mimo`（小米）、`miku-gpt`（旧 key，留作 503 故障样本） |
| 别名降级 | `gpt-5.4-mini` → `miku-gpt` → `ark` → `mimo`，请求方只认一个名字，路由负责改写上游模型名 |
| 显式锁渠道 | 头 `X-Router-Pin: ark` 或模型名前缀 `ark:doubao-seed-2.1-turbo`；调试/灰度/比效果时让自动选路让位给人（冷却中的渠道也照发） |
| 熔断 | 瞬态失败（5xx/429/408/超时）计数开闸，冷却后半开放行，成功即清零；确定性失败（404/402/400）只换渠道不熔断 |
| 启动期能力探测 | `GET /v1/models` 校准声明模型，key 没权益的模型直接摘掉 |
| 成本账 | `data/usage.jsonl` 逐笔落盘，聚合用 `python ops/usage-report.py` |
| `/v1/models` | 全站唯一清单：TTS 引擎 + LLM 渠道 + 别名三路合并，带 `kind`/`owners`/`available` |
| `/ui/api/status` | `channels` 数组：优先级、模型、是否在冷却、价目是否已知 |

规则细节见 `docs/routing-policy.md`，渠道实测数据见 `docs/channel-verification-miku-20261029.md`。

## 快速开始

```bash
# key 只走环境变量，仓库根 .env 是 gitignored 的
set -a; . ../../.env; set +a

mvn package
java -jar target/model-router-0.1.0-SNAPSHOT.jar --server.port=8410

# 非流式
curl -X POST http://127.0.0.1:8410/v1/chat/completions \
  -H "Authorization: Bearer sk-local-dev-key" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash","messages":[{"role":"user","content":"say ok"}]}'

# 流式（加了 -N 才能看到逐帧输出）
curl -N -X POST http://127.0.0.1:8410/v1/chat/completions \
  -H "Authorization: Bearer sk-local-dev-key" -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash","messages":[{"role":"user","content":"say ok"}],"stream":true}'

python ../../ops/usage-report.py     # 成本聚合：按天 / 渠道 / 模型
```

两个环境坑：终端里配了 `HTTP_PROXY` 的话，curl 打 localhost 会被代理吃掉（返回假的 502），要加 `--noproxy '*'`；用 `&` 起的 java 进程会随 shell 调用结束被回收，起服务请用受管的后台任务。

## 验证到什么程度

- **47 个测试全绿 + CI 绿**
- **恢复演练**（`ChannelRecoveryTest`）：本地 HttpServer 造一个「先坏后好」的渠道，走真实 HTTP 验证
  连 503 → 开闸 → 冷却期内被跳过 → 半开放行成功 → 回到主档。**只会降级不会恢复的熔断器等于把渠道一次性判死刑**，所以这条单独测。
- **真流量三条路径**：`deepseek-v4.1-flash` → 200 走 miku 主档；`gpt-5.4-mini` → miku-gpt 失败后落 ark 200；
  `gpt-5.2` → 502，body 里带逐渠道失败原因。
- **真实故障样本**：miku 新 key 的 gpt-5.x 全 404（key 没权益）、miku 旧 key 的 gpt-5.4-mini 持续 503、
  deepseek 真 402（余额），三条都是线上撞出来的，不是 mock。

## 还没做的

- LLM 侧配额 / 预算硬止损（TTS 侧有日字数配额，LLM 侧还没有）
- 语义缓存、按延迟/错误率的动态权重选路、首 token 延迟纳入选路
- 本地 LM Studio 兜底档（早期设计稿里写了，实际没做）
- GLM / DashScope 渠道：`.env` 里那两个 key 实测是**占位符**（`your_zhipu_api_key_here` /
  `your_dashscope_api_key_here`），端点返回 401，有真实 key 才能接

## 踩坑记录

- **中转渠道的可用模型跟着 key 走**。同一个 base-url 换把 key，权益可能完全不相交——写死模型清单只会在请求期稳定吃 404，所以要有启动期探测。
- **探测失败不能误杀渠道**。ark 的 `/api/plan/v3/models` 探测不通，若按「不符就禁用」会摘掉一个正常渠道；现在的规则是部分不符才摘，全部不符保守保留。
- **价目未知记 0 等于编造假账**。第三方中转没有公开价目，记 0 就是把「不知道」写成「免费」。
- **流式没有头可用**。token 数只在最后一个 SSE 帧里，得旁路抄表（`SseUsageStream`），传完再落账。
- **相同路径的两个 mapping 会撞**（当年 `/v1/models` 的 Ambiguous mapping），模型清单这类聚合端点要收口到一个控制器。
