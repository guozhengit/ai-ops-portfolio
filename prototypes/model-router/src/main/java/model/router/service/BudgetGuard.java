package model.router.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import model.router.config.ChannelProperties;
import model.router.config.RouterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 日级预算硬止损（V2-D2.3）——TTS 侧 Quota + UsageLedger 形态的平移。
 *
 * <p>两层各说各的真话（预注册决策 #3，docs/v2-phase-plan.md）：账本层纪律不动，
 * 价目未知 costUsd=null 照旧（未知≠0）；护栏层需要数字，未知成本按上界估记
 * （注册表最贵已知价目或兜底单价 × tokens），只进预算判断、只落 budget-events.jsonl，
 * 永不写进 usage.jsonl。日桶按 UTC 自然日（Clock 可注入，测试可推时钟）。
 */
@Component
public class BudgetGuard {

  private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 一次预算判定：allowed + 当日已用（known/estimated 分列）+ 日预算上限。 */
  public record Decision(boolean allowed, double spentKnown, double spentEstimated, double dailyUsd) {}

  private final boolean enabled;
  private final double dailyUsd;
  private final double reservePerCallUsd;
  private final double estimateFallbackPerMTokOut;
  private final Path eventsPath;
  private final Clock clock;
  /** UTC 日 → {knownUsd, estimatedUsd}；跨天自然分桶，旧桶保留作审计。 */
  private final ConcurrentHashMap<String, double[]> days = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public BudgetGuard(RouterProperties router, ChannelProperties channelProps) {
    this(router.getBudget().isEnabled(), router.getBudget().getDailyUsd(),
        router.getBudget().getReservePerCallUsd(), router.getBudget().getEstimateFallbackPerMTokOut(),
        eventsPathOf(channelProps), Clock.systemUTC());
  }

  public BudgetGuard(boolean enabled, double dailyUsd, double reservePerCallUsd,
                     double estimateFallbackPerMTokOut, Path eventsPath, Clock clock) {
    this.enabled = enabled;
    this.dailyUsd = dailyUsd;
    this.reservePerCallUsd = reservePerCallUsd;
    this.estimateFallbackPerMTokOut = estimateFallbackPerMTokOut;
    this.eventsPath = eventsPath;
    this.clock = clock;
  }

  /** 测试直构用：护栏关闭，preCheck 恒放行。 */
  public static BudgetGuard disabled() {
    return new BudgetGuard(false, 0, 0, 0, null, Clock.systemUTC());
  }

  private static Path eventsPathOf(ChannelProperties props) {
    try {
      return Path.of(props.getUsagePath()).resolveSibling("budget-events.jsonl");
    } catch (Exception e) {
      return null;
    }
  }

  /** 前置检查：当日已用（known + estimated）+ 本笔预留 ≤ 日预算才放行。 */
  public synchronized Decision preCheck() {
    if (!enabled) {
      return new Decision(true, 0, 0, dailyUsd);
    }
    double[] day = days.computeIfAbsent(today(), k -> new double[2]);
    boolean allowed = day[0] + day[1] + reservePerCallUsd <= dailyUsd;
    return new Decision(allowed, day[0], day[1], dailyUsd);
  }

  /**
   * 调用成功后入账：已知成本进 known；未知成本按上界估记进 estimated（不碰 usage.jsonl）。
   * estPricePerMTokOut 由调用方给（注册表最贵已知输出价，无已知价目时用兜底单价）。
   */
  public synchronized void recordCall(Double costUsd, Integer promptTokens,
                                      Integer completionTokens, double estPricePerMTokOut) {
    if (!enabled) {
      return;
    }
    double[] day = days.computeIfAbsent(today(), k -> new double[2]);
    if (costUsd != null) {
      day[0] += costUsd;
      return;
    }
    int p = promptTokens == null ? 0 : promptTokens;
    int c = completionTokens == null ? 0 : completionTokens;
    double estimated = (p + c) / 1_000_000.0 * estPricePerMTokOut;
    day[1] += estimated;
    appendEvent("cost_estimated", Map.of(
        "estimatedUsd", estimated,
        "estPricePerMTokOut", estPricePerMTokOut,
        "promptTokens", p, "completionTokens", c));
    log.info("[budget] unknown cost estimated at ${} (est price ${}/MTok, {}+{} tokens)",
        estimated, estPricePerMTokOut, p, c);
  }

  /** 拒绝留痕（append-only，证据纪律与 usage.jsonl 同源）。 */
  public synchronized void logDenial(Decision decision) {
    appendEvent("budget_denied", Map.of(
        "spentKnownUsd", decision.spentKnown(),
        "spentEstimatedUsd", decision.spentEstimated(),
        "dailyUsd", decision.dailyUsd()));
  }

  public boolean enabled() {
    return enabled;
  }

  public double fallbackEstimatePerMTokOut() {
    return estimateFallbackPerMTokOut;
  }

  private String today() {
    return LocalDate.now(clock).toString();
  }

  private void appendEvent(String event, Map<String, Object> fields) {
    if (eventsPath == null) {
      return;
    }
    try {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("ts", Instant.now(clock).toString());
      row.put("event", event);
      row.putAll(fields);
      if (eventsPath.getParent() != null) {
        Files.createDirectories(eventsPath.getParent());
      }
      Files.writeString(eventsPath, MAPPER.writeValueAsString(row) + "\n",
          StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("[budget] event append failed: {}", e.toString());
    }
  }
}
