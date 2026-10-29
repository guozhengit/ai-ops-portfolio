# tts-router

OpenAI 兼容的多引擎 TTS 路由器。定位：**便宜 VPS（2C2G）只跑路由和云兜底，本地 GPU 机器跑模型侧车**，路由器负责鉴权、配额、计量、缓存、按优先级选路和失败降级。

## 技术栈与理由

| 层 | 选型 | 理由 |
| --- | --- | --- |
| 路由器 | Java 21 + Spring Boot 3.5.16 + Maven | 本机已装 JDK 21（`D:/jdk/jdk-21.0.8`）与 Maven 3.9.9，本地仓库已缓存 Boot 3.5.16；鉴权/配额/计量/出账这类 ToB 逻辑用主栈写最省事 |
| Web | spring-boot-starter-web（MVC，非 WebFlux） | 音频请求是「一问一答的二进制响应」，不需要响应式；Reactor 的心智负担换不到收益 |
| 引擎侧 | Python + FastAPI 侧车（未实现） | Kokoro / CosyVoice 只有 Python 运行时，路由器不该去追模型依赖 |
| 存储 | H2 文件库（开发）→ Postgres（生产）+ Flyway | 现在这台机器 Docker 引擎没跑，先用 H2 把计量链路跑通；DDL 保持两家兼容 |
| 缓存 | 磁盘文件缓存，按总量淘汰最久未用 | 音频块放不进 2G 内存，内存 LRU 在这里是错的答案 |
| HTTP 出向 | JDK `java.net.http.HttpClient` | 零额外依赖，支持超时与字节流 |
| 未采用 | Go（本机没装）、Node/TS（要另装一套运行时且与主栈无关）、WebFlux | — |

关键约束：引擎侧统一只出 WAV，mp3 由路由器调用本机 ffmpeg（`D:\Program Files\ffmpeg\bin\ffmpeg.exe`，9.0.2 GPL shared）经 stdin/stdout 管道转换，避免每个引擎各实现一遍转码。

## 控制台 UI

启动后打开 `http://localhost:17777/`（纯静态页 + 原生 JS，无构建步骤）：

- **状态展示无需鉴权**：引擎列表（优先级 / 模型 / 熔断冷却状态）、今日请求与字符数、缓存占用、配额信息，数据来自公开的 `GET /ui/api/status`（只暴露聚合值，不含 api-keys）。
- **语音合成面板**：模型 / 音色 / 语速 / 格式选择，结果内嵌播放并可下载，显示 `X-Engine` / `X-Cache` / 用量。每次合成后自动刷新用量卡片。
- **鉴权约定**：`tts.api-keys` 非空时页面要求填一次 key（存 localStorage）；`api-keys` 为空时服务端匿名放行，页面自动隐藏 key 输入框。`/v1/*` 的鉴权逻辑不变。

## 跑起来

```bash
# Maven 默认挂在 JDK 8 上，必须显式覆盖 JAVA_HOME
JAVA_HOME="D:/jdk/jdk-21.0.8" mvn -B -DskipTests compile
JAVA_HOME="D:/jdk/jdk-21.0.8" mvn -B spring-boot:run

curl -H "Authorization: Bearer sk-local-dev-key" http://localhost:17777/v1/models

curl -X POST http://localhost:17777/v1/audio/speech \
  -H "Authorization: Bearer sk-local-dev-key" -H "Content-Type: application/json" \
  -d '{"model":"dev-kokoro","voice":"zf_xiaoxiao","input":"你好，世界"}' \
  -o out.wav

# mp3（走 ffmpeg 管道转换）
curl -X POST http://localhost:17777/v1/audio/speech \
  -H "Authorization: Bearer sk-local-dev-key" -H "Content-Type: application/json" \
  -d '{"model":"dev-kokoro","voice":"zf_xiaoxiao","input":"你好，世界","response_format":"mp3"}' \
  -o out.mp3
```

`dev` profile 自带两个假引擎（一个必失败、一个产正弦波 WAV），不需要 GPU 和外网就能验证路由链路。真实引擎在 `application-prod.yml` 里按 `base-url + models + priority` 声明，侧车只要实现 `POST /synthesize`（JSON 进、音频字节出）即可接入。

## 测试

手工 curl 场景已固化为自动化测试（dev 假引擎 + 内存 H2 + 独立缓存目录，ffmpeg 不在时 mp3 用例自动跳过）：

```bash
JAVA_HOME="D:/jdk/jdk-21.0.8" mvn -B test
```

覆盖：401 / 降级到健康引擎 / 缓存 HIT / 不支持的格式 / 未知模型 / 超长输入切分拼接 / 切分硬顶 400 / mp3 转码 / 状态页公开 / 429 配额，外加 TextSplitter、WavMerger、AudioConverter（含 ffmpeg 缺失报错）与 AudioCache（读写 / LRU 淘汰 / 统计）单元测试。

## 已验证行为（2026-10-27，dev profile，curl 实测）

| 场景 | 结果 |
| --- | --- |
| 无 Authorization 访问 `/v1/*` | 401 `authentication_error` |
| `dev-broken`（priority 1）失败 | WARN 记录后自动降级到 `dev-fake`，响应头 `X-Engine: dev-fake` |
| 同一请求第二次 | `X-Cache: HIT`、`X-Engine: cache`，磁盘存在 `sha256.wav` |
| `response_format=mp3` | 200 `audio/mpeg`，23,084 字节 WAV 转出 8,492 字节 MP3，文件头 `ID3`，ffprobe 识别为 mp3 |
| mp3 重复请求 | `X-Cache: HIT`，缓存目录同时存在 `.wav` 与 `.mp3`，证明缓存键按格式分区 |
| `response_format=wav` | 不转码直通（`wav_bytes == out_bytes`） |
| `response_format=ogg` | 400 `unsupported_response_format`，不静默兜底 |
| 未知 model | 400 `model_not_found` |
| 超日配额 | 429 `insufficient_quota`，消息里的 `used 72` 是重启后从 H2 读出来的，证明计量真的落库 |

踩过的坑：`mvn -q ... | tail` 之后取 `$?` 拿到的是 `tail` 的退出码，编译失败被吞掉，进程照样起得来，只是 `target/classes` 残缺，运行期报 `NoClassDefFoundError`。构建校验统一改成 `mvn -B clean compile > log 2>&1; echo $?` 并确认目标 class 文件存在。

## 2026-10-28 迭代记录

长文本切分与拼接（原来超过引擎上限直接 400）：

- 超过候选引擎最小 `max-input-chars` 的文本自动切成多片：片长按候选引擎的最小上限定，保证降级链路上任何引擎都接得住每一片；每片独立走选路/降级/熔断。
- 切分优先句末标点（`。！？!?；;\n…. `），窗口内没有强边界时退到弱边界（`，,、：:`），最后才硬切。
- 分片各自合成后做 **PCM 拼接**（校验采样参数一致，重写 RIFF/data 长度，不一致报 500 不硬拼出杂音），mp3 只转码一次。
- 硬顶 `tts.split.max-total-chars`（默认 100000），防止一次请求打出成百上千个引擎调用；`tts.split.enabled` 可整体关闭回到旧的 400 行为。
- 缓存键仍按全文计算，重复长文本直接命中；`X-Engine` 在多引擎合片时以 `+` 连接。

修复与加固：

- **AudioConverter**：stdin/stdout/stderr 三根管道都可能独立卡死，改为各放一个虚拟线程 Future 共享同一个 deadline，任何一段超时就 `destroyForcibly` 断开管道——之前 ffmpeg 挂起会永久占死请求线程。超时改为可配置（`tts.audio.timeout`，默认 30s）。
- **逐引擎字符校验**：文本超过某引擎的 `max-input-chars` 就跳过该引擎，全部超限才 400。之前只看最高优先级引擎，会误杀降级链路上本可接单的引擎。
- **熔断错误分类**：`EngineException` 带 `transientFailure` 标记，5xx/429/网络/超时计入冷却，确定性 4xx 只降级不计熔断——之前一次错配就能把引擎打进 60 秒冷却。
- **缓存淘汰修复**：`Stream.toList()` 返回不可变列表导致淘汰排序抛 `UnsupportedOperationException`（原实现只要触发驱逐就必崩，测试首次覆盖才暴露）；命中时刷新 mtime，淘汰语义从 FIFO 变成真正的 LRU；总大小改为内存估算 + 驱逐后校准，put 不再每次全目录扫描；新增 `stats()`。
- **安全**：API key 改为 SHA-256 + `MessageDigest.isEqual` 恒时比较（遍历全部 key 不提前退出）；未处理异常不再回传类名/消息，只给可在日志检索的 ref 号；请求体非法 JSON 从 500 改为 400。
- **UI**：修复周期状态刷新会把用户正在输入的 key 冲掉的问题。

## 结构

```
src/main/java/tts/router/
  config/RouterProperties.java     # api-keys / quota / cache / health / audio / engines
  provider/                        # TtsEngine SPI + HttpEngine + EngineRegistry(熔断冷却/健康快照) + dev 假引擎
  service/                         # SpeechService(选路/降级/长文本切分) / TextSplitter / WavMerger / AudioCache(磁盘 LRU) / AudioConverter(ffmpeg) / UsageLedger(计量配额)
  web/                             # SpeechController / StatusController(状态页公开API) / ApiKeyAuthFilter / GlobalErrorHandler
src/main/resources/static/         # 控制台 UI（index.html + app.js + style.css，状态无需鉴权）
src/test/java/                     # 集成测试(RouterIntegrationTest / QuotaIntegrationTest) + 单元测试
```

## 未做

引擎健康探针（主动探测而非被动熔断）、`/admin` 管理接口、Docker 部署、Postgres 实测迁移。
