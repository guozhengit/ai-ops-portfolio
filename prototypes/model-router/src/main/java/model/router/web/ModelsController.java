package model.router.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.EngineRegistry;

/**
 * /v1/models —— 全站唯一的模型清单出口，把三路来源合成一张表：
 *
 * <ol>
 *   <li>TTS 引擎的模型（冻结的语音轴，仍在服务）</li>
 *   <li>LLM 渠道声明且经启动期探测校准过的模型（owned_by = 渠道 id）</li>
 *   <li>别名（kind=alias）——请求方真正会用的名字，比如 gpt-5.4-mini
 *       本身没有任何渠道直供，它是「先 miku-gpt、再 ark、再 mimo」这条降级链的入口</li>
 * </ol>
 *
 * <p>为什么要单列别名：请求方拿 /v1/models 挑模型，如果别名不在清单里，
 * 他会以为这个名字不能用，转而去直连上游——那路由就白做了。
 */
@RestController
@RequestMapping("/v1")
public class ModelsController {

  private final EngineRegistry engines;
  private final ChannelRegistry channels;
  private final ChannelProperties properties;

  public ModelsController(EngineRegistry engines, ChannelRegistry channels,
                          ChannelProperties properties) {
    this.engines = engines;
    this.channels = channels;
    this.properties = properties;
  }

  @GetMapping("/models")
  public Map<String, Object> models() {
    Map<String, ModelEntry> byId = new TreeMap<>();

    engines.modelOwners().forEach((model, owner) -> entry(byId, model).add("tts", owner, true));

    Map<String, ChannelRegistry.ChannelStatus> statusById = new LinkedHashMap<>();
    for (ChannelRegistry.ChannelStatus status : channels.statuses()) {
      statusById.put(status.id(), status);
      for (String model : status.models()) {
        entry(byId, model).add("llm", status.id(), status.available());
      }
    }

    for (Map.Entry<String, List<String>> alias : properties.getAliases().entrySet()) {
      List<String> owners = new ArrayList<>();
      for (String target : alias.getValue()) {
        int colon = target.indexOf(':');
        if (colon <= 0) {
          continue;
        }
        String channelId = target.substring(0, colon);
        // 链上不存在的渠道直接跳过：降级链里有死条目不能当成可用对外报
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
      // 别名自己不拥有模型，owners 就是这条降级链上还活着的渠道
      ModelEntry modelEntry = entry(byId, alias.getKey());
      owners.forEach(owner -> modelEntry.add("alias", owner, aliasAvailable));
    }

    List<Map<String, Object>> data = byId.values().stream().map(ModelEntry::toJson).toList();
    return Map.of("object", "list", "data", data);
  }

  private static ModelEntry entry(Map<String, ModelEntry> byId, String id) {
    return byId.computeIfAbsent(id, ModelEntry::new);
  }

  /** 同一个模型名可能同时是 TTS 引擎模型、LLM 渠道模型、别名入口，这里把来源并起来而不是互相覆盖。 */
  private static final class ModelEntry {

    private final String id;
    private final Set<String> kinds = new TreeSet<>();
    private final Set<String> owners = new LinkedHashSet<>();
    private boolean available;

    ModelEntry(String id) {
      this.id = id;
    }

    void add(String kind, String owner, boolean ownerAvailable) {
      kinds.add(kind);
      owners.add(owner);
      available |= ownerAvailable;
    }

    Map<String, Object> toJson() {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("object", "model");
      item.put("created", 0);
      item.put("owned_by", owners.isEmpty() ? "router" : owners.iterator().next());
      item.put("kind", String.join("+", kinds));
      item.put("owners", List.copyOf(owners));
      item.put("available", available);
      return item;
    }
  }
}
