package model.router.provider;

import java.util.List;

/** 测试辅助：从现成 LlmChannel 实例组装注册表（不经配置装配）。 */
public final class ChannelRegistryTestSupport {

  private ChannelRegistryTestSupport() {
  }

  public   static ChannelRegistry registryOf(int failureThreshold, LlmChannel... channels) {
    List<ChannelRegistry.ChannelBinding> bindings = java.util.Arrays.stream(channels)
        .map(c -> new ChannelRegistry.ChannelBinding(c, 1))
        .toList();
    model.router.config.ChannelProperties props = new model.router.config.ChannelProperties();
    return new ChannelRegistry(bindings, failureThreshold, java.time.Duration.ofSeconds(60), props);
  }
}
