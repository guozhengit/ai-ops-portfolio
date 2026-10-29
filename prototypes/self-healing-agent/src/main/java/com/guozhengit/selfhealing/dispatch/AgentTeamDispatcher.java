package com.guozhengit.selfhealing.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guozhengit.selfhealing.alert.AlertPayloads;
import com.guozhengit.selfhealing.approval.ActionExecutor;
import com.guozhengit.selfhealing.approval.ApprovalStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * D10：AgentScope harness Team 诊断编排（原型 1 的核心）。
 *
 * <p>告警进 webhook 去重后转派到这里，Team 按职责拆分：
 * lead（编排汇总）→ metrics-analyst（PromQL 只读查询 160 Prometheus + chaos-demo 状态）→
 * log-analyst（观测数据原文）→ remedy-planner（结构化方案）。
 * 白板全结算后，lead 输出带证据链的 Markdown 诊断报告落盘。
 *
 * <p>安全边界：所有工具均为只读 HTTP（Prometheus 查询 / chaos-demo 观测端点），
 * 无 shell、无写操作；修复动作的沙箱执行与人工审批是下一阶段（sandbox-verifier）。
 */
@Component
@ConditionalOnProperty(name = "selfhealing.agent.enabled", havingValue = "true")
public class AgentTeamDispatcher implements IncidentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentTeamDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final int maxRounds;
    private final String prometheusUrl;
    private final String chaosBaseUrl;
    private final Path reportsDir;
    private final ApprovalStore approvals;
    private final ActionExecutor executor;
    private final String fallbackApiKey;
    private final String fallbackBaseUrl;
    private final String fallbackModel;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;

    public AgentTeamDispatcher(
            @Value("${selfhealing.agent.api-key}") String apiKey,
            @Value("${selfhealing.agent.base-url}") String baseUrl,
            @Value("${selfhealing.agent.model}") String modelName,
            @Value("${selfhealing.agent.max-rounds:6}") int maxRounds,
            @Value("${selfhealing.prometheus.url}") String prometheusUrl,
            @Value("${selfhealing.chaos.base-url}") String chaosBaseUrl,
            @Value("${selfhealing.reports-dir:reports}") String reportsDir,
            ApprovalStore approvals,
            ActionExecutor executor,
            @Value("${selfhealing.agent.fallback.api-key:}") String fallbackApiKey,
            @Value("${selfhealing.agent.fallback.base-url:}") String fallbackBaseUrl,
            @Value("${selfhealing.agent.fallback.model:}") String fallbackModel,
            @Value("${selfhealing.agent.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${selfhealing.agent.retry.backoff-ms:2000}") long retryBackoffMs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("诊断模型未配置：请通过 AGENT_API_KEY / ARK_API_KEY 环境变量提供");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.maxRounds = maxRounds;
        this.prometheusUrl = prometheusUrl;
        this.chaosBaseUrl = chaosBaseUrl;
        this.reportsDir = Path.of(reportsDir);
        this.approvals = approvals;
        this.executor = executor;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackModel = fallbackModel;
        this.retryMaxAttempts = retryMaxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public void dispatch(AlertPayloads.AlertItem alert, String rawBody) {
        String fp = alert.fingerprint() == null ? "nofp" : alert.fingerprint();
        String teamName = "diag-" + fp + "-" + Instant.now().toEpochMilli() % 100000;
        long start = System.currentTimeMillis();
        log.info("[diag] 开始诊断 alert={} fingerprint={} team={}", alert.alertName(), fp, teamName);
        try {
            String report;
            boolean degraded = false;
            try {
                report = runTeam(teamName, alert, apiKey, baseUrl, modelName);
            } catch (Exception primaryEx) {
                if (fallbackApiKey == null || fallbackApiKey.isBlank()) {
                    throw primaryEx;
                }
                degraded = true;
                log.warn("[diag] primary 模型({})整场失败，降级 fallback({})：{}",
                        modelName, fallbackModel, primaryEx.toString());
                report = runTeam(teamName + "-fb", alert, fallbackApiKey, fallbackBaseUrl, fallbackModel);
            }
            Path saved = saveReport(fp, alert, report, degraded);
            log.info("[diag] 诊断完成（{}ms），报告已落盘: {}", System.currentTimeMillis() - start, saved);
            proposeActions(fp, alert.alertName(), saved, report);
        } catch (Exception e) {
            log.error("[diag] 诊断失败 alert={}: {}", fp, e.toString(), e);
        }
    }

    /** 单次 LLM 调用重试：指数退避（backoff * 2^i），用尽后抛出交由上层降级。 */
    private Msg callWithRetry(ReActAgent agent, String prompt, Duration timeout) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return agent.call(prompt).block(timeout);
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("[diag] LLM 调用失败（第 {}/{} 次）：{}", attempt, retryMaxAttempts,
                        ex.getClass().getSimpleName() + ": " + rootMessage(ex));
                if (attempt < retryMaxAttempts) {
                    Thread.sleep(retryBackoffMs * (1L << (attempt - 1)));
                }
            }
        }
        throw last;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m.substring(0, Math.min(160, m.length()));
    }

    /**
     * D11：从诊断报告提取修复动作 → 白名单内低风险动作自动沙箱执行，
     * 其余进入人工审批队列；所有动作与结果追加进报告文件。
     */
    private void proposeActions(String fp, String alertName, Path reportPath, String reportText) {
        StringBuilder record = new StringBuilder("\n## 审批与执行记录（D11）\n\n");
        try {
            ReActAgent extractor = ReActAgent.builder()
                    .name("action-extractor")
                    .sysPrompt("你是运维动作提取器。只输出 JSON 数组，不要输出任何其他文字。")
                    .model(model())
                    .toolkit(new Toolkit())
                    .build();
            String prompt = "从下面的诊断报告提取修复动作，输出 JSON 数组，每项格式：\n"
                    + "{\"description\":\"动作描述\",\"risk\":\"low|medium|high\",\"executable_command\":\"...\"}\n"
                    + "executable_command 只能取 chaos:off:<scene>（scene ∈ memory-leak|slow-sql|disk-full|pool-exhaustion|nacos-flap）"
                    + "或 null（无安全执行通道）。最多 5 项。\n\n报告：\n" + reportText;
            Msg r = callWithRetry(extractor, prompt, Duration.ofMinutes(3));
            String text = r != null ? r.getTextContent() : "";
            int s = text.indexOf('[');
            int e = text.lastIndexOf(']');
            if (s < 0 || e <= s) {
                record.append("- 未能解析出结构化动作（模型未按 JSON 输出），跳过。");
            } else {
                JsonNode arr = MAPPER.readTree(text.substring(s, e + 1));
                for (JsonNode item : arr) {
                    String desc = item.path("description").asText("(无描述)");
                    String risk = item.path("risk").asText("unknown");
                    String cmd = item.path("executable_command").asText(null);
                    boolean cmdOk = cmd != null && ActionExecutor.whitelisted(cmd);
                    ApprovalStore.ApprovalRequest apr = approvals.create(fp, alertName, desc, risk, cmdOk ? cmd : null);
                    if (cmdOk && "low".equalsIgnoreCase(risk)) {
                        // 白名单 + 低风险：按策略自动批准并执行（审计里 decidedBy 留痕）
                        approvals.decide(apr.id, "approve", "auto-policy:whitelisted-low-risk");
                        ActionExecutor.ExecResult result = executor.execute(cmd);
                        approvals.markExecuted(apr, result.output(), result.ok());
                        record.append("- ✅ **自动执行**（白名单+低风险）`").append(cmd).append("`：")
                                .append(result.output()).append("（审计 id=").append(apr.id).append("）\n");
                        log.info("[actions] 自动执行 {} -> {}", cmd, result.ok());
                    } else {
                        record.append("- ⏸ **待人工审批** risk=").append(risk)
                                .append(cmd != null ? "（有执行通道，批准后自动执行）" : "（无安全执行通道，批准也仅记录）")
                                .append("：").append(desc).append("（id=`").append(apr.id).append("`）\n");
                        log.info("[actions] 待人工审批 id={} risk={}", apr.id, risk);
                    }
                }
            }
        } catch (Exception ex) {
            record.append("- 动作提取失败：").append(ex.getClass().getSimpleName()).append(" ").append(ex.getMessage());
            log.warn("[actions] 提取失败: {}", ex.toString());
        }
        try {
            Files.writeString(reportPath, record + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.error("[actions] 报告追加失败: {}", ex.toString());
        }
    }

    /** 搭建一次性诊断 Team 并跑完编排循环，返回 lead 的最终报告文本。 */
    private String runTeam(String teamName, AlertPayloads.AlertItem alert,
                           String apiKey, String baseUrl, String modelName) throws Exception {
        BaseStore store = new InMemoryStore();
        TeamClient client = new LocalTeamClient(store);
        String objective = "诊断告警 " + alert.alertName() + "（fingerprint=" + alert.fingerprint()
                + "，severity=" + alert.labels().getOrDefault("severity", "?")
                + "）：" + alert.annotations().getOrDefault("summary", "");
        client.createTeam(new TeamCreateSpec(teamName, "default", objective, "lead",
                "lead 拆解诊断目标、派发任务、汇总证据生成诊断报告",
                List.of(new TeamMemberSpec("metrics", "metrics-analyst", "指标分析 worker", "byo"),
                        new TeamMemberSpec("logs", "log-analyst", "观测数据 worker", "byo"),
                        new TeamMemberSpec("remedy", "remedy-planner", "修复方案 worker", "byo"))))
                .block(Duration.ofSeconds(10));

        Model model = model(apiKey, baseUrl, modelName);

        ReActAgent lead = buildMember(client, teamName, "lead",
                "你是诊断团队 lead。目标：" + objective
                        + "。先 listTasks 看板，把诊断拆成任务并 assignTask：指标证据给 metrics、"
                        + "观测原文给 logs、修复方案给 remedy；所有任务 settled 后，调用 completeTeam 之前，"
                        + "先汇总产出最终 Markdown 诊断报告（结构：## 结论 / ## 证据链（引用各任务 result 原文）/ "
                        + "## 修复建议（动作/风险/是否需人工审批）/ ## 后续观察项），把报告全文作为你的回复输出。",
                null, null, model);
        ReActAgent metrics = buildMember(client, teamName, "metrics",
                "你是指标分析 worker。listClaimableTasks 找到你的任务后 claimTask，"
                        + "用 prom_query 工具对告警相关指标做 PromQL 查询取证据（可选指标前缀 chaos_），"
                        + "再调用 chaos_status 看靶场当前状态，把发现写进 completeTask。",
                null, new PromTools.PromQueryTool(prometheusUrl, chaosBaseUrl), model);
        ReActAgent logs = buildMember(client, teamName, "logs",
                "你是观测数据 worker。listClaimableTasks 找到你的任务后 claimTask，"
                        + "用 chaos_metrics 工具拉取靶场指标原文作为数据证据，把关键行摘录写进 completeTask。",
                null, new PromTools.ChaosMetricsTool(chaosBaseUrl), model);
        ReActAgent remedy = buildMember(client, teamName, "remedy",
                "你是修复方案 worker。listClaimableTasks 找到你的任务后 claimTask，"
                        + "基于队友写入白板的证据产出结构化建议（每条含：动作/预期效果/风险等级/是否需人工审批），"
                        + "写进 completeTask。只做方案，不执行。",
                null, null, model);

        // Round 0：lead 拆派
        callWithRetry(lead, "目标：" + objective, Duration.ofMinutes(4));
        // 兜底：lead 没建任务就程序化建，保证流程可继续
        if (client.listTasks("default", teamName).block(Duration.ofSeconds(10)).isEmpty()) {
            client.createTask("default", teamName, "取证：相关指标查询", "用 prom_query / chaos_status 取证据", List.of(), "metrics").block();
            client.createTask("default", teamName, "取证：观测数据原文", "用 chaos_metrics 拉取原文摘录", List.of(), "logs").block();
            client.createTask("default", teamName, "产出修复方案", "基于白板证据出结构化建议", List.of(), "remedy").block();
        }
        String summary = "";
        for (int round = 1; round <= maxRounds; round++) {
            log.info("[diag] round {} worker 执行", round);
            callWithRetry(metrics, "继续执行团队分配给你的任务。", Duration.ofMinutes(4));
            callWithRetry(logs, "继续执行团队分配给你的任务。", Duration.ofMinutes(4));
            callWithRetry(remedy, "继续执行团队分配给你的任务。", Duration.ofMinutes(4));
            if (allTerminal(client, teamName)) {
                log.info("[diag] round {} 白板全结算，lead 生成报告", round);
                Msg r = callWithRetry(lead, "所有任务已 settled。请汇总产出最终 Markdown 诊断报告并 completeTeam，报告全文作为回复输出。",
                        Duration.ofMinutes(5));
                summary = r != null ? r.getTextContent() : "";
                break;
            }
            Msg r = callWithRetry(lead, "查看白板：若所有任务已 settled，请汇总结论并 completeTeam；否则继续派发或催办。",
                    Duration.ofMinutes(4));
            if (r != null && allTerminal(client, teamName)) {
                summary = r.getTextContent();
                break;
            }
        }
        if (summary.isBlank()) {
            summary = "> 诊断未在轮次内收敛，白板状态见 journal（后续轮次可继续排查）";
        }
        return summary;
    }

    private boolean allTerminal(TeamClient client, String team) {
        List<TeamTask> tasks = client.listTasks("default", team).block(Duration.ofSeconds(10));
        return tasks != null && !tasks.isEmpty()
                && tasks.stream().allMatch(t -> TeamTask.isTerminal(t.state()));
    }

    private ReActAgent buildMember(TeamClient client, String team, String role, String sysPrompt,
                                   TeamContext ctxPlaceholder, Object workerTool, Model model) {
        TeamContext ctx = new TeamContext(team, "default",
                "诊断目标见 team objective", role, "lead".equals(role), null, null);
        TeamsMiddleware mw = new TeamsMiddleware(client, ctx);
        Toolkit tk = new Toolkit();
        tk.registerTool(mw.getTools().get(0));
        if (workerTool != null) {
            tk.registerTool(workerTool);
        }
        return ReActAgent.builder()
                .name(role)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(tk)
                .middlewares(List.of(mw))
                .build();
    }

    private Model model() {
        return model(apiKey, baseUrl, modelName);
    }

    private static Model model(String apiKey, String baseUrl, String modelName) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(modelName).stream(true).build();
    }

    private Path saveReport(String fp, AlertPayloads.AlertItem alert, String report, boolean degraded) throws Exception {
        Files.createDirectories(reportsDir);
        String file = "incident-" + Instant.now().toString().replace(":", "").replace("-", "")
                + "-" + fp + ".md";
        String head = "# 诊断报告：" + alert.alertName() + "\n\n"
                + "- fingerprint: `" + fp + "`\n"
                + "- severity: " + alert.labels().getOrDefault("severity", "?") + "\n"
                + "- 告警摘要: " + alert.annotations().getOrDefault("summary", "") + "\n"
                + "- 生成时间: " + Instant.now() + "\n"
                + "- 诊断模型: " + modelName + "（团队编排：lead/metrics/logs/remedy）\n"
                + (degraded
                        ? "- ⚠️ **降级标记**：主模型（" + modelName + "）本场多次重试后失败，本次报告由 fallback 模型（"
                                + fallbackModel + "）完成，证据链可信度需人工复核。\n"
                        : "")
                + "\n---\n\n";
        Path out = reportsDir.resolve(file);
        Files.writeString(out, head + report + "\n", StandardCharsets.UTF_8);
        return out;
    }

    /** 只读工具集：Prometheus PromQL 查询 + chaos-demo 观测端点。 */
    static class PromTools {

        static class PromQueryTool {
            private final String promUrl;
            private final String chaosBase;

            PromQueryTool(String promUrl, String chaosBase) {
                this.promUrl = promUrl;
                this.chaosBase = chaosBase;
            }

            @Tool(name = "prom_query", description = "对 Prometheus 执行只读 PromQL 即时查询，返回 JSON 结果。指标前缀 chaos_ 的是靶场指标。")
            public String query(@ToolParam(name = "query", description = "PromQL 表达式，例如 chaos_memory_rss_bytes 或 rate(chaos_db_query_seconds_count[5m])") String query) {
                try {
                    String url = promUrl + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET()
                            .timeout(Duration.ofSeconds(15)).build();
                    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    JsonNode node = MAPPER.readTree(resp.body());
                    JsonNode result = node.path("data").path("result");
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode m : result) {
                        sb.append(m.path("metric").toString()).append(" => ")
                                .append(m.path("value").path(1).asText()).append("\n");
                    }
                    return sb.isEmpty() ? "无数据（status=" + node.path("status").asText() + "）" : sb.toString().trim();
                } catch (Exception e) {
                    return "查询失败: " + e.getClass().getSimpleName() + " " + e.getMessage();
                }
            }

            @Tool(name = "chaos_status", description = "获取 chaos-demo 靶场当前状态（活跃故障/RSS/磁盘/连接池/Nacos）。")
            public String status() {
                return httpGet(chaosBase + "/status");
            }
        }

        static class ChaosMetricsTool {
            private final String chaosBase;

            ChaosMetricsTool(String chaosBase) {
                this.chaosBase = chaosBase;
            }

            @Tool(name = "chaos_metrics", description = "拉取 chaos-demo 的 Prometheus 指标原文（chaos_ 前缀行）。")
            public String metrics() {
                String all = httpGet(chaosBase + "/metrics");
                return all.lines().filter(l -> l.startsWith("chaos_") || l.startsWith("# TYPE chaos_"))
                        .limit(60).reduce((a, b) -> a + "\n" + b).orElse("(空)");
            }
        }

        private static String httpGet(String url) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET()
                        .timeout(Duration.ofSeconds(15)).build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.body();
            } catch (Exception e) {
                return "请求失败: " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }
    }
}
