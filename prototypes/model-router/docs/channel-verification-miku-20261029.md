# 渠道验证：MikuAPI（`https://mikuapi.org/v1`）

日期：2026-10-29 23:40 · 验证人：本机直连（不走路由器，裸 curl / urllib 打真实 endpoint）
被测凭据：用户提供的新 key（`sk-9aa…`，**未落盘、未入库**，仅本次进程内使用；配置中只引用环境变量名）
对照凭据：`.env` 中原有的 `MIKU_API_KEY`（`sk-8d0…`，脱敏引用）

---

## 一句话结论

**这个渠道能用，但只能用 `deepseek-v4.1-flash` 这一个模型。** 你 opencode 配置里列的那一串 gpt-5.x，
对这把 key 全部返回 404 `model_not_found`——那串模型名是 opencode 端写死的选择器，不是这把 key 的实际权益。
更关键的是：**旧 key 和新 key 的权益完全不相交**（旧 key 能碰 gpt-5.x 但全 503，新 key 能碰 deepseek 但碰不到 gpt），
说明「渠道」这个抽象必须按 **key 维度**建模，不能按供应商维度。

---

## 证据

### 1. 模型清单由 key 权益决定，不是平台全量

| 调用 | 新 key 结果 |
| --- | --- |
| `GET /v1/models` | 200，但只有 2 个：`deepseek-v4.1-flash`、`deepseek-v4.1-flash-1017` |
| `POST /chat/completions` `gpt-5.4-mini` | 404 `Model "gpt-5.4-mini" is not supported by any configured account in this group` |
| `gpt-5.2` / `gpt-5.4` / `gpt-5.5` / `gpt-5.6` | 同上，404（逐字一致） |
| `deepseek-v4.1-flash` | 200，content=ok |

错误信息里的 **"in this group"** 就是答案：同一个平台下不同 key 分属不同 group，group 决定可用模型。

### 2. 两把 key 权益不相交（决定性对照）

| 模型 | 旧 key `sk-8d0…` | 新 key `sk-9aa…` |
| --- | --- | --- |
| `gpt-5.4-mini` | **503** `Service temporarily unavailable`（group 认这个模型，上游挂了） | **404** `not supported … in this group`（group 根本不认） |
| `deepseek-v4.1-flash` | 404 不认 | **200** 正常 |

这两种错误在我路由器里的处置是相反的，正好各自都撞上了真实样本：
503 = 瞬态 → 计数、开闸、冷却；404 = 确定性失败 → 只换渠道，**不熔断**（熔断一个「配置错了」的渠道没有意义）。

### 3. 可用性与延迟（新 key，`deepseek-v4.1-flash`）

- 连续 11 次采样：200 × 11；非流式平均 3.74s，最快 2.36s，最慢 6.36s
- 同批次更早一次调用撞到 **502 Bad Gateway**（15 次里 1 次）——真实瞬态故障，熔断器的输入
- 参数兼容性：
  - `reasoning_effort: low/high` → 200 接受（low 4.2s / high 5.8s，符合推理模型的耗时特征）
  - `temperature` → 200 接受
  - `variant: high`（opencode 那套变体参数）→ 200 接受，但**看不出生效**，判断为被静默忽略
- 流式 SSE：200，29~56 帧，`[DONE]` 正常收尾；**usage 出现在带 `finish_reason: stop` 的那一帧**，
  加不加 `stream_options.include_usage` 都给

### 4. 计费口径的坑

非流式返回的 usage 长这样：

```json
{"prompt_tokens":37,"completion_tokens":22,
 "completion_tokens_details":{"reasoning_tokens":19}}
```

22 个 completion token 里 **19 个是推理 token**。这是推理模型，输出侧单价乘的是含推理的总量，
比 chat 模型贵一截——成本账必须按「这个渠道的实际 token 构成」解释，不能只看单价表。

---

## 对 model-router 的四个直接影响

1. **渠道 = baseUrl + key + 模型集合**，三者缺一不可。同一个 baseUrl 配两把 key 就是两个渠道，
   各自声明各自的模型集。现在配置里 `miku` 声明 `models: [gpt-5.4-mini, gpt-5.2, gpt-5.3-codex]`，
   对新 key 而言三个全是死条目。
2. **404 不该等到请求期才发现**。启动期拿 `GET /v1/models` 校准一次声明列表，把不支持的模型从渠道里摘掉，
   比每次请求试错再降级干净得多（也省掉一次无谓的跨渠道跳转）。
3. **价格出处问题（纪律问题，不是技术问题）**。MikuAPI 是第三方中转，没有官方公开价目，
   当前配置写死的 `0.30 / 1.20` 是我拍的，不是查到的。按「成本数字必须有出处」的规矩，
   这种情况应该记 `cost: null`（未知）而不是记 `0`——记 0 等于编造一个便宜的假账。
4. **旧 key 的 503 是有价值的样本**，别急着删：它是唯一能复现「瞬态故障→熔断→切渠道」的现成来源，
   新 key 只能复现「确定性失败→换渠道」。两条路径各留一个真实故障源，演练才完整。

---

## 已据此落地（2026-10-29 当晚）

- [x] 新 key 成为主档渠道 `miku`（priority 1，models `[deepseek-v4.1-flash]`）；旧 key 独立成
      `miku-gpt`（priority 9）保留 gpt-5.x 侧，作为 503 瞬态故障演练源
- [x] 价目未知 → 账本记 `"costUsd":null,"costKnown":false`，不再记 0
- [x] 启动期能力探测（`router.probe-models`）：实测 miku 声明命中权益、`miku-gpt` 声明全保留
      （旧 key 的 `/v1/models` 确实列着 gpt-5.x）、**ark 探测无结论 → 保守保留声明**
      （`/api/plan/v3/models` 这条路径不可用于探测，正好验证了「探测失败不能误杀渠道」这条规则）
- [x] 真流量演练三条：
      `deepseek-v4.1-flash` → 200 走 miku 主档；`gpt-5.4-mini` → miku-gpt 失败后落到 ark 200；
      `gpt-5.2` → 只有 miku-gpt 支持 → 502，body 带逐渠道失败原因
- [x] 账本实证：`{"channel":"miku","promptTokens":37,"completionTokens":37,
      "costUsd":null,"costKnown":false}`
- [x] 404 / 503 固化成自动化测试：`HttpChannelStatusTest` 用本地 HttpServer 打真实状态码，
      断言 404/400/401/402 → 确定性（不熔断），500/502/503/429/408 → 瞬态（计数开闸）
- [x] SSE 尾帧 usage 精确计费：新增 `SseUsageStream` 旁路抄表（字节原样透传，顺手记下 usage）。
      真流量实证：263 帧 / 74KB 流式响应 → 账本行
      `{"channel":"miku","promptTokens":35,"completionTokens":262,"costUsd":null,"costKnown":false,"latencyMs":7251,"stream":true}`
      ——token 精确了，成本依然是 null：价目未知这件事不会因为 token 数知道了就变得已知。

---

## 复测：2026-10-31（1-token 探测，key 未落盘未回显）

| 渠道 | key | 模型 | 结果 |
| --- | --- | --- | --- |
| miku 主档 | 新 key | deepseek-v4.1-flash | **200，2713ms**（2026-10-29 深夜的 502 瞬态未复现） |
| miku-gpt | 旧 key | gpt-5.4-mini | **502 `upstream_error`「Upstream access forbidden」**（503→502，上游仍未恢复） |

影响：原型 1「主模型路径 MTTD 补测」（gpt-5.4-mini 走旧 key）继续阻塞；主档 deepseek-v4.1-flash
路径已可用，可先行以主档为主模型补测一场（口径需在文章①里注明更换）。
