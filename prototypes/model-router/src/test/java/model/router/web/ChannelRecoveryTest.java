package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.HttpChannel;
import model.router.provider.LlmChannel;
import model.router.service.UsageJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 熔断恢复演练：渠道连续 503 → 开闸 → 冷却 → 半开放行 → 成功 → 回到主档。
 *
 * <p>为什么单独测这一条：之前的演练只证明了「会降级」，没证明「能回来」。
 * 只会降级不会恢复的熔断器，本质上是一次性把渠道判了死刑——生产上没人敢用。
 * 这里用本地 HttpServer 造一个「先坏后好」的渠道，全过程走真实 HTTP（不是 mock 出来的异常）。
 */
class ChannelRecoveryTest {

  @TempDir
  Path tempDir;

  @Test
  void channelRecoversAfterCooldownAndReturnsToPrimary() throws Exception {
    AtomicInteger flakyCalls = new AtomicInteger();
    HttpServer flaky = server(exchange -> {
      int n = flakyCalls.incrementAndGet();
      if (n <= 2) {
        respond(exchange, 503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}");
      } else {
        respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
      }
    });
    HttpServer backup = server(exchange -> respond(exchange, 200,
        "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
    flaky.start();
    backup.start();

    try {
      ChannelProperties props = new ChannelProperties();
      props.setUsagePath(tempDir.resolve("usage.jsonl").toString());
      LlmChannel primary = channel("flaky", "http://127.0.0.1:" + flaky.getAddress().getPort() + "/v1");
      LlmChannel fallback = channel("backup", "http://127.0.0.1:" + backup.getAddress().getPort() + "/v1");
      ChannelRegistry registry = new ChannelRegistry(
          List.of(new ChannelRegistry.ChannelBinding(primary, 1),
              new ChannelRegistry.ChannelBinding(fallback, 2)),
          2, Duration.ofMillis(300), props);
      ChatController controller = new ChatController(registry, new UsageJournal(props));

      String body = "{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}";

      // 第 1、2 次：flaky 仍会被尝试（失败计数 1 → 2，达到阈值开闸），请求落到 backup
      assertEquals("backup", channelOf(controller, body));
      assertEquals("backup", channelOf(controller, body));
      assertFalse(available(registry, "flaky"), "两次瞬态失败后应开闸");
      assertNotNull(cooldownUntil(registry, "flaky"), "开闸要有冷却截止时间");

      // 第 3 次：冷却期内 flaky 直接被跳过，不再浪费一次注定失败的调用
      assertEquals("backup", channelOf(controller, body));

      // 冷却结束：半开放行，此时上游已经恢复 → 成功，渠道回到主档
      Thread.sleep(400);
      assertEquals("flaky", channelOf(controller, body), "冷却结束后应半开放行并成功回到主档");
      assertTrue(available(registry, "flaky"), "恢复成功后不该还在冷却");

      // 再打一次，确认它是真的回来了而不是只放行了一发
      assertEquals("flaky", channelOf(controller, body));

      long rows;
      try (Stream<String> lines = Files.lines(tempDir.resolve("usage.jsonl"))) {
        rows = lines.count();
      }
      assertEquals(5, rows, "5 次成功调用应各记一行成本账");
    } finally {
      flaky.stop(0);
      backup.stop(0);
    }
  }

  private static String channelOf(ChatController controller, String body) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat(body, null, response);
    assertEquals(200, response.getStatus());
    return response.getHeader("X-Router-Channel");
  }

  private static boolean available(ChannelRegistry registry, String id) {
    return registry.statuses().stream()
        .filter(status -> status.id().equals(id))
        .findFirst().orElseThrow().available();
  }

  private static java.time.Instant cooldownUntil(ChannelRegistry registry, String id) {
    return registry.statuses().stream()
        .filter(status -> status.id().equals(id))
        .findFirst().orElseThrow().cooldownUntil();
  }

  private static LlmChannel channel(String id, String baseUrl) {
    ChannelProperties.Channel config = new ChannelProperties.Channel();
    config.setId(id);
    config.setBaseUrl(baseUrl);
    config.setApiKeyEnv("MODEL_ROUTER_TEST_KEY_NOT_SET");
    config.setModels(List.of("m1"));
    config.setTimeout(Duration.ofSeconds(10));
    return new HttpChannel(config);
  }

  private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", handler);
    return server;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }
}
