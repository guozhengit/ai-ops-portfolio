package model.router.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import model.router.config.RouterProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.EngineRegistry;
import model.router.service.ModelCatalog;
import model.router.service.UsageJournal;
import model.router.service.AudioCache;
import model.router.service.UsageLedger;

/**
 * 状态页的公开数据源：只暴露引擎健康、模型清单、当日总量与缓存占用，不含 api-keys。
 * 鉴权过滤器只挂在 /v1/*，这里天然匿名可访问。
 */
@RestController
@RequestMapping("/ui/api")
public class StatusController {

  private final EngineRegistry registry;
  private final UsageLedger ledger;
  private final AudioCache cache;
  private final RouterProperties properties;
  private final ChannelRegistry channels;
  private final UsageJournal journal;
  private final ModelCatalog catalog;

  public StatusController(EngineRegistry registry, UsageLedger ledger, AudioCache cache,
      RouterProperties properties, ChannelRegistry channels, UsageJournal journal,
      ModelCatalog catalog) {
    this.registry = registry;
    this.ledger = ledger;
    this.cache = cache;
    this.properties = properties;
    this.channels = channels;
    this.journal = journal;
    this.catalog = catalog;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    List<Map<String, Object>> engines = registry.statuses().stream()
        .map(engine -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", engine.id());
          item.put("priority", engine.priority());
          item.put("models", engine.models());
          item.put("available", engine.available());
          item.put("cooldownUntil", engine.cooldownUntil());
          return item;
        })
        .toList();

    // 模型清单与 /v1/models 同源（ModelCatalog）：控制台看到的必须就是入口能用的那份
    List<Map<String, Object>> models = catalog.entries().stream()
        .map(item -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", item.id());
          row.put("kind", item.kind());
          row.put("owners", item.owners());
          row.put("available", item.available());
          return row;
        })
        .toList();

    Map<String, UsageJournal.UsageSummary> byChannel = journal.todayByChannel();
    UsageJournal.UsageSummary llmUsage = journal.today();

    // LLM 渠道健康：优先级、模型、是否在熔断冷却、价目是否已知（未知的账单记 null）+ 今日用量
    List<Map<String, Object>> llmChannels = channels.statuses().stream()
        .map(status -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", status.id());
          item.put("priority", status.priority());
          item.put("models", status.models());
          item.put("available", status.available());
          item.put("cooldownUntil", status.cooldownUntil());
          item.put("priceKnown", status.priceKnown());
          UsageJournal.UsageSummary usage = byChannel.get(status.id());
          item.put("today", usage == null ? null : Map.of(
              "calls", usage.calls(),
              "promptTokens", usage.promptTokens(),
              "completionTokens", usage.completionTokens(),
              "knownCostUsd", usage.knownCostUsd(),
              "unknownCostCalls", usage.unknownCostCalls()));
          return item;
        })
        .toList();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("authRequired", !properties.getApiKeys().isEmpty());
    payload.put("engines", engines);
    payload.put("channels", llmChannels);
    payload.put("models", models);
    payload.put("llmUsage", Map.of(
        "calls", llmUsage.calls(),
        "promptTokens", llmUsage.promptTokens(),
        "completionTokens", llmUsage.completionTokens(),
        "knownCostUsd", llmUsage.knownCostUsd(),
        "unknownCostCalls", llmUsage.unknownCostCalls()));
    // 语音侧的用量（TTS 轴冻结保留，但账还是要给人看）
    payload.put("usage", ledger.totalsToday());
    payload.put("quota", Map.of(
        "enabled", properties.getQuota().isEnabled(),
        "dailyChars", properties.getQuota().getDailyChars()));
    payload.put("cache", cache.stats());
    payload.put("formats", List.of("wav", "mp3"));
    return payload;
  }
}
