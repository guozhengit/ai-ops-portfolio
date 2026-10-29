package model.router.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import model.router.config.ChannelProperties;
import model.router.config.ChannelProperties.Channel;

/**
 * LLM 渠道注册表：选路（能力匹配 → 优先级 → 同级按输出价升序）+ 熔断（共享 CircuitBreaker）。
 * 缺 key（环境变量不存在）的渠道在装配期直接禁用并告警——半配置的渠道比没有渠道更危险。
 */
@Component
public class ChannelRegistry {

  private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

  public record ChannelBinding(LlmChannel channel, int priority) {}

  public record ChannelStatus(String id, int priority, List<String> models, boolean available,
                              Instant cooldownUntil, boolean keyPresent, Double pricePerMTokIn,
                              Double pricePerMTokOut, boolean priceKnown) {}

  private final List<ChannelBinding> bindings;
  private final CircuitBreaker breaker;
  private final ChannelProperties properties;

  @Autowired
  public ChannelRegistry(ChannelProperties properties) {
    this(bindFrom(properties), properties.getHealth().getFailureThreshold(),
        properties.getHealth().getCooldown(), properties);
  }

  public ChannelRegistry(List<ChannelBinding> bindings, int failureThreshold,
                  java.time.Duration cooldown, ChannelProperties properties) {
    this.breaker = new CircuitBreaker(failureThreshold, cooldown);
    this.properties = properties;
    // 同优先级按输出单价升序；单价未知（null）排在同优先级最后——没价目的渠道不该优先花钱
    this.bindings = bindings.stream()
        .sorted(Comparator.comparingInt(ChannelBinding::priority)
            .thenComparing(b -> b.channel().pricePerMTokOut(),
                Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
    this.bindings.forEach(binding -> log.info("channel registered id={} priority={} models={}",
        binding.channel().id(), binding.priority(), binding.channel().models()));
  }

  private static List<ChannelBinding> bindFrom(ChannelProperties properties) {
    List<ChannelBinding> bindings = new ArrayList<>();
    Map<String, List<String>> entitled = new java.util.LinkedHashMap<>();
    for (Channel config : properties.getChannels()) {
      if (config.resolveApiKey().isBlank()) {
        log.warn("channel={} disabled: env {} not set", config.id(), config.apiKeyEnv());
        continue;
      }
      List<String> actual = applyCapabilityProbe(config, properties);
      if (actual != null) {
        entitled.put(config.id(), actual);
      }
      if (config.models().isEmpty()) {
        log.warn("channel={} disabled: no model left after capability probe", config.id());
        continue;
      }
      bindings.add(new ChannelBinding(new HttpChannel(config), config.priority()));
      log.info("channel configured id={} priority={} models={} priceIn={}/MTok priceOut={}/MTok",
          config.id(), config.priority(), config.models(), config.pricePerMTokIn(),
          config.pricePerMTokOut());
    }
    pruneAliases(properties, entitled);
    return bindings;
  }

  /**
   * 启动期用 GET /models 校准声明模型。返回该 key 的实际权益（null = 探测无结论）。
   * 保守规则：部分不符就摘掉不符的那几个；**全部不符**更像是 /models 口径不一致而不是配置写错，
   * 这时保留声明清单并报错日志，避免一个探测口径差异把整个渠道误杀。
   */
  private static List<String> applyCapabilityProbe(Channel config, ChannelProperties properties) {
    if (!properties.isProbeModels()) {
      return null;
    }
    List<String> actual = CapabilityProbe.probeModels(config, properties.getProbeTimeout());
    if (actual == null) {
      log.warn("channel={} capability probe inconclusive (GET /models unusable), keep declared {}",
          config.id(), config.models());
      return null;
    }
    List<String> kept = CapabilityProbe.supported(config.models(), actual);
    List<String> dropped = config.models().stream().filter(m -> !kept.contains(m)).toList();
    if (dropped.isEmpty()) {
      return actual;
    }
    if (kept.isEmpty()) {
      log.error("channel={} declared models {} fully mismatch GET /models {} — "
          + "likely a listing-format difference, keep declared (do not disable the channel)",
          config.id(), config.models(), actual);
      return actual;
    }
    log.warn("channel={} dropped models not entitled by this key: {} (kept {})",
        config.id(), dropped, kept);
    config.setModels(new ArrayList<>(kept));
    return actual;
  }

  /** 别名里指向「已被探测摘掉的模型」的条目一并删掉，否则降级会跳到一个注定 404 的上游。 */
  private static void pruneAliases(ChannelProperties properties, Map<String, List<String>> entitled) {
    for (Map.Entry<String, List<String>> entry : properties.getAliases().entrySet()) {
      List<String> original = entry.getValue();
      List<String> kept = original.stream()
          .filter(alias -> {
            String[] parts = alias.split(":", 2);
            if (parts.length != 2) {
              return true;
            }
            List<String> actual = entitled.get(parts[0]);
            return actual == null || actual.contains(parts[1]);
          })
          .toList();
      if (kept.size() != original.size()) {
        List<String> dropped = original.stream().filter(a -> !kept.contains(a)).toList();
        log.warn("alias={} dropped targets not entitled by their channel: {}", entry.getKey(),
            dropped);
        entry.setValue(new ArrayList<>(kept));
      }
    }
  }

  /** 支持该模型且未在熔断冷却中的渠道，按选路顺序返回；别名展开为渠道专属上游模型名。 */
  public List<ChannelTarget> candidates(String model) {
    Instant now = Instant.now();
    List<ChannelTarget> targets = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (ChannelBinding binding : bindings) {
      LlmChannel channel = binding.channel();
      String upstreamModel = resolveUpstream(channel, model);
      if (upstreamModel == null || !breaker.isAvailable(channel.id(), now)) {
        continue;
      }
      if (seen.add(channel.id())) {
        targets.add(new ChannelTarget(channel, upstreamModel));
      }
    }
    return targets;
  }

  /** 渠道直供该模型 → 原名；否则查别名表「渠道id:上游模型」。 */
  private String resolveUpstream(LlmChannel channel, String requestedModel) {
    if (channel.supports(requestedModel)) {
      return requestedModel;
    }
    for (String entry : properties.getAliases().getOrDefault(requestedModel, List.of())) {
      String[] parts = entry.split(":", 2);
      if (parts.length == 2 && parts[0].equals(channel.id())) {
        return parts[1];
      }
    }
    return null;
  }

  /** 一次路由决定：用哪个渠道 + 上游真正收到的模型名。 */
  public record ChannelTarget(LlmChannel channel, String upstreamModel) {}

  public void recordSuccess(String channelId) {
    breaker.recordSuccess(channelId);
  }

  public void recordFailure(String channelId, boolean transientFailure) {
    breaker.recordFailure(channelId, transientFailure, Instant.now());
  }

  public List<ChannelStatus> statuses() {
    Instant now = Instant.now();
    List<ChannelStatus> result = new ArrayList<>();
    for (ChannelBinding binding : bindings) {
      LlmChannel channel = binding.channel();
      CircuitBreaker.BreakerStatus status = breaker.status(channel.id(), now);
      result.add(new ChannelStatus(channel.id(), binding.priority(), channel.models(),
          status.available(), status.cooldownUntil(), true,
          channel.pricePerMTokIn(), channel.pricePerMTokOut(),
          channel.pricePerMTokIn() != null && channel.pricePerMTokOut() != null));
    }
    return result;
  }
}
