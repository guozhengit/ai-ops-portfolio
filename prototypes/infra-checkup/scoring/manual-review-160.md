# 160 真机人工核对记录（外部裁判，2026-10-30）

对着 `http://192.168.1.160:9091/api/v1/targets` 的 32 个 activeTargets 逐条看，
不借助检查器输出。判定依据只有两类：target 的 `health` 和 `lastError` 原文，
加上本机对 159 的连通性实测。

## 主机聚合后的人工判定

| 主机 | 目标数 | up/down | lastError 关键片段 | 人工结论 | 严重级 |
| --- | --- | --- | --- | --- | --- |
| 192.168.1.159 | 20 | 0/20 | connection refused（实测 ping/22/9091 全断） | 主机级失联，本机 DHCP 已变 .16，配置陈旧 | P1 |
| clickhouse | 1 | 0/1 | `lookup clickhouse on 127.0.0.11:53: no such host` | 配了不存在的容器名，配置漂移 | P1 |
| kafka-exporter | 1 | 0/1 | `lookup kafka-exporter ... no such host` | 同上 | P1 |
| qdrant | 1 | 0/1 | `lookup qdrant ... no such host` | 同上 | P1 |
| rocketmq-exporter | 1 | 0/1 | `lookup rocketmq-exporter ... no such host` | 同上 | P1 |
| 其余 8 台 | 各 1 | 全 up | — | 正常 | — |

人工应报 5 条（1 条主机级 + 4 条配置漂移），全部 P1。

## 这个 1.0 是怎么来的（诚实交代）

最终评分 precision=1.000 / recall=1.000 / 漏报 0 / 误报 0，但**不是一次跑出来的**。
人工核对过程反过来揭出了工具的两个真缺陷，改完才对上：

1. **初版把 159 的 20 个目标逐条报，产出 24 条 P1 噪音**，而且判定依赖我手工填进
   `known_dead_hosts` 的基线——等于工具只是复述人已经知道的死 IP。改成主机级聚合 +
   无基线自推断（同 IP 多目标同时 down 即推断主机故障）后，收敛成 1 条 P1。
   分支留痕：2026-10-30 留档真机跑时本地基线未清，159 走的是**基线确认分支**
   （findings JSONL meta `confirmed_by_baseline: true, self_inferred: false`）；
   无基线的自推断路径由单测 `test_self_infer_without_baseline` 覆盖，两条路以 meta 字段区分。
2. **初版把 clickhouse/kafka/qdrant/rocketmq 降成 P2「需人工确认」**。人工看
   `lastError` 才发现全是 Docker DNS `no such host`——这不是「服务可能临时挂了」，
   是配置里写了根本不存在的容器名，确定性配置错误。补 DNS 判定后升为 P1。

另外，对**真实仓库**（不是 fixtures 靶场）跑 credscan 时，初版报了 22 条，
其中 17 条是误报，人工逐条归因后修掉：

| 误报类型 | 例子 | 根因 | 修法 |
| --- | --- | --- | --- |
| 合规密钥文件被当泄露 | `.env` 里的 ARK/MIKU/DEEPSEEK key | `.env` 已被 gitignore 覆盖，放真 key 是设计意图 | 用 `git check-ignore` 精确判定，被忽略的降 P2 并改措辞 |
| 环境变量名被当值 | `api-key-env: MIKU_API_KEY` | 值是变量名不是凭据值 | 识别纯大写下划线标识符 |
| 第三方库常量 | jinja2 `TOKEN_BLOCK_BEGIN` | `.venv-chaos/site-packages` 没跳过 | 补 SKIP_DIRS + IGNORE_KEY |
| 占位符变体 | `sk-change-me`、`lm-studio` | 锚定正则挡不住前缀 | 片段匹配 + 本地占位集合 |
| 入库模板被当风险 | `.env.example` | `!.env.example` 故意放行且已 tracked | 模板名白名单，未忽略不报警 |

真实仓库产出从 22 条假告警收敛到 5 条 P2（全是「已被 gitignore 保护的本地密钥文件」
提示），**误报清零**。

## 口径边界（不藏）

- 评分只覆盖 drift 维度。credscan 的准确性由 fixtures 黄金用例（45 个单测）保证，
  真仓库的 5 条 P2 是提示性发现，没有独立二值真值，不进 precision/recall。
- exposure 的 0 条只说明「Prometheus 抓取清单里没有高危端口」。SSH 免密不通，
  没做真实全端口扫描，不能推断「160 上没有高危端口暴露」。
- `expected_targets` 基线为空，本轮不做目标清单增删比对（已登记 degraded）。
- 靶场 fixtures 里故意埋的假凭据与真机发现分区呈现，不计入真实告警数。
