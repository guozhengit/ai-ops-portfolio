package model.router.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
                              Instant cooldownUntil, boolean keyPresent, double pricePerMTokIn,
                              double pricePerMTokOut) {}

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
    this.bindings = bindings.stream()
        .sorted(Comparator.comparingInt(ChannelBinding::priority)
            .thenComparingDouble(b -> b.channel().pricePerMTokOut()))
        .toList();
    this.bindings.forEach(binding -> log.info("channel registered id={} priority={} models={}",
        binding.channel().id(), binding.priority(), binding.channel().models()));
  }

  private static List<ChannelBinding> bindFrom(ChannelProperties properties) {
    List<ChannelBinding> bindings = new ArrayList<>();
    for (Channel config : properties.getChannels()) {
      if (config.resolveApiKey().isBlank()) {
        log.warn("channel={} disabled: env {} not set", config.id(), config.apiKeyEnv());
        continue;
      }
      bindings.add(new ChannelBinding(new HttpChannel(config), config.priority()));
      log.info("channel configured id={} priority={} priceIn={}/MTok priceOut={}/MTok",
          config.id(), config.priority(), config.pricePerMTokIn(), config.pricePerMTokOut());
    }
    return bindings;
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
          channel.pricePerMTokIn(), channel.pricePerMTokOut()));
    }
    return result;
  }
}
