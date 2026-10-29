package model.router.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.EngineRegistry;

/**
 * 模型清单的唯一来源：TTS 引擎 + LLM 渠道 + 别名，三路合并去重。
 *
 * <p>为什么抽出来：之前 /v1/models 和 /ui/api/status 各算各的，status 那份只认 TTS 模型，
 * 于是控制台看到的清单和真实入口的清单对不上——两个数据源必然漂移。现在两边共用这一份。
 */
@Component
public class ModelCatalog {

  public record CatalogEntry(String id, String kind, List<String> owners, boolean available) {}

  private final EngineRegistry engines;
  private final ChannelRegistry channels;
  private final ChannelProperties properties;

  public ModelCatalog(EngineRegistry engines, ChannelRegistry channels,
                      ChannelProperties properties) {
    this.engines = engines;
    this.channels = channels;
    this.properties = properties;
  }

  public List<CatalogEntry> entries() {
    Map<String, Entry> byId = new TreeMap<>();

    engines.modelOwners().forEach((model, owner) -> entry(byId, model).add("tts", owner, true));

    Map<String, ChannelRegistry.ChannelStatus> statusById = new LinkedHashMap<>();
    for (ChannelRegistry.ChannelStatus status : channels.statuses()) {
      statusById.put(status.id(), status);
      for (String model : status.models()) {
        entry(byId, model).add("llm", status.id(), status.available());
      }
    }

    // 别名是请求方真正会用的名字，不进清单的话请求方就会绕过路由直连上游
    for (Map.Entry<String, List<String>> alias : properties.getAliases().entrySet()) {
      List<String> owners = new ArrayList<>();
      for (String target : alias.getValue()) {
        int colon = target.indexOf(':');
        if (colon <= 0) {
          continue;
        }
        String channelId = target.substring(0, colon);
        if (statusById.containsKey(channelId)) {
          owners.add(channelId);
        }
      }
      if (owners.isEmpty()) {
        continue;
      }
      boolean aliasAvailable = owners.stream()
          .map(statusById::get)
          .anyMatch(ChannelRegistry.ChannelStatus::available);
      Entry modelEntry = entry(byId, alias.getKey());
      owners.forEach(owner -> modelEntry.add("alias", owner, aliasAvailable));
    }

    return byId.values().stream()
        .map(item -> new CatalogEntry(item.id, String.join("+", item.kinds),
            List.copyOf(item.owners), item.available))
        .toList();
  }

  /** 该模型能否走 LLM 轴（控制台的试调下拉只该出现这些，TTS 模型喂进去必然 503）。 */
  public List<CatalogEntry> chatCapable() {
    return entries().stream()
        .filter(item -> item.kind().contains("llm") || item.kind().contains("alias"))
        .toList();
  }

  private static Entry entry(Map<String, Entry> byId, String id) {
    return byId.computeIfAbsent(id, Entry::new);
  }

  /** 同一个名字可能同时是 TTS 模型、渠道模型、别名入口，来源是「并」不是「覆盖」。 */
  private static final class Entry {

    private final String id;
    private final Set<String> kinds = new TreeSet<>();
    private final Set<String> owners = new LinkedHashSet<>();
    private boolean available;

    Entry(String id) {
      this.id = id;
    }

    void add(String kind, String owner, boolean ownerAvailable) {
      kinds.add(kind);
      owners.add(owner);
      available |= ownerAvailable;
    }
  }
}
