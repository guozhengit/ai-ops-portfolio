package model.router.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import model.router.config.ChannelProperties.Channel;
import org.junit.jupiter.api.Test;

/**
 * 启动期能力探测：权益清单解析、探测无结论的处理、以及「声明 ∩ 权益」的摘除规则。
 * 真实背景见 docs/channel-verification-miku-20261029.md：同一 base-url 的两把 key 权益完全不相交。
 */
class CapabilityProbeTest {

  @Test
  void declaredModelsAreNarrowedToActualEntitlement() {
    List<String> kept = CapabilityProbe.supported(
        List.of("gpt-5.4-mini", "deepseek-v4.1-flash", "gpt-5.2"),
        List.of("deepseek-v4.1-flash", "deepseek-v4.1-flash-1017"));
    assertEquals(List.of("deepseek-v4.1-flash"), kept, "只有 key 真正有权益的模型该留下");
  }

  @Test
  void probeReturnsNullWhenModelsEndpointUnusable() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/models", exchange -> {
      byte[] body = "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      Channel channel = channel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      assertNull(CapabilityProbe.probeModels(channel, Duration.ofSeconds(5)),
          "探测无结论时必须返回 null，让调用方保留声明清单");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void probeParsesEntitledModelIds() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/models", exchange -> {
      byte[] body = ("{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-v4.1-flash\"},"
          + "{\"id\":\"deepseek-v4.1-flash-1017\"}]}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      Channel channel = channel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      List<String> ids = CapabilityProbe.probeModels(channel, Duration.ofSeconds(5));
      assertEquals(2, ids.size());
      assertTrue(ids.contains("deepseek-v4.1-flash"));
    } finally {
      server.stop(0);
    }
  }

  private static Channel channel(String baseUrl) {
    Channel channel = new Channel();
    channel.setId("probe");
    channel.setBaseUrl(baseUrl);
    channel.setApiKeyEnv("MODEL_ROUTER_TEST_KEY_NOT_SET");
    return channel;
  }
}
