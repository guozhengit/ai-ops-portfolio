package model.router.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import model.router.config.RouterProperties;
import model.router.provider.EngineRegistry;
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

  public StatusController(EngineRegistry registry, UsageLedger ledger, AudioCache cache,
      RouterProperties properties) {
    this.registry = registry;
    this.ledger = ledger;
    this.cache = cache;
    this.properties = properties;
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

    List<Map<String, String>> models = registry.modelOwners().entrySet().stream()
        .map(entry -> Map.of("id", entry.getKey(), "owned_by", entry.getValue()))
        .toList();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("authRequired", !properties.getApiKeys().isEmpty());
    payload.put("engines", engines);
    payload.put("models", models);
    payload.put("usage", ledger.totalsToday());
    payload.put("quota", Map.of(
        "enabled", properties.getQuota().isEnabled(),
        "dailyChars", properties.getQuota().getDailyChars()));
    payload.put("cache", cache.stats());
    payload.put("formats", List.of("wav", "mp3"));
    return payload;
  }
}
