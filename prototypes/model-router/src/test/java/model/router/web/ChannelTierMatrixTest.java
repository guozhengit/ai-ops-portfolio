package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.HttpChannel;
import model.router.provider.LlmChannel;
import model.router.service.UsageJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 渠道能力分层矩阵（V2-D2.2，借 EverOS tier1/2/3 + upgrade path 测试法）。
 *
 * <p>tier1 单渠道可用；tier2 瞬态/确定性失败语义分治（确定性只换道不熔断）；
 * tier3 全渠道健康（优先级+价目选路、成本当场可见）；upgrade path = 装配期缺 key
 * 禁用不炸 + 上游修复后零配置回归轮转。全部内嵌 HttpServer 真实状态码，CI 稳定不依赖外网。
 */
class ChannelTierMatrixTest {

  @TempDir
  Path tempDir;

  private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
      + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

  @Test
  void tier1_singleHealthyChannelServesAllTraffic() throws Exception {
    HttpServer only = okServer();
    only.start();
    try {
      ChannelRegistry registry = registry(
          List.of(binding("only", url(only), 1)), props(tempDir));
      ChatController controller = new ChatController(registry, new UsageJournal(props(tempDir)));

      assertEquals("only", channelOf(controller));
      assertEquals("only", channelOf(controller), "单渠道稳态：全部流量归它，无降级噪音");
      assertEquals(1, registry.statuses().size());
    } finally {
      only.stop(0);
    }
  }

  @Test
  void tier2_transientFailuresBreakCircuitWhileDeterministicOnesOnlySwitch() throws Exception {
    // 主渠道剧本：503（瞬态）→ 402（确定性）→ 503（瞬态，计满开闸）→ 200（恢复）
    AtomicInteger calls = new AtomicInteger();
    HttpServer primary = server(exchange -> {
      int n = calls.incrementAndGet();
      switch (n) {
        case 1 -> respond(exchange, 503, "{\"error\":{\"message\":\"unavailable\"}}");
        case 2 -> respond(exchange, 402, "{\"error\":{\"message\":\"insufficient quota\"}}");
        case 3 -> respond(exchange, 503, "{\"error\":{\"message\":\"unavailable\"}}");
        default -> respond(exchange, 200, OK);
      }
    });
    HttpServer backup = okServer();
    primary.start();
    backup.start();
    try {
      ChannelRegistry registry = registry(
          List.of(binding("primary", url(primary), 1), binding("backup", url(backup), 2)), props(tempDir));
      ChatController controller = new ChatController(registry, new UsageJournal(props(tempDir)));

      assertEquals("backup", channelOf(controller), "503 瞬态：换道");
      assertTrue(available(registry, "primary"), "1 次瞬态失败未达阈值，不应开闸");

      assertEquals("backup", channelOf(controller), "402 确定性：同样换道");
      assertTrue(available(registry, "primary"),
          "确定性失败（402 余额不足）只换道不熔断——熔断器语义分治的核心断言");

      assertEquals("backup", channelOf(controller), "再吃一次 503：瞬态计满");
      assertFalse(available(registry, "primary"), "两次瞬态失败达到阈值，开闸");

      Thread.sleep(300);
      assertEquals("primary", channelOf(controller), "冷却结束半开放行，上游已恢复应回主档");
      assertTrue(available(registry, "primary"));
    } finally {
      primary.stop(0);
      backup.stop(0);
    }
  }

  @Test
  void tier3_priorityThenPriceOrderingWithCostVisible() throws Exception {
    AtomicInteger lowPriorityCalls = new AtomicInteger();
    HttpServer cheap = okServer();
    HttpServer pricey = okServer();
    HttpServer lowPriority = server(exchange -> {
      lowPriorityCalls.incrementAndGet();
      respond(exchange, 200, OK);
    });
    cheap.start();
    pricey.start();
    lowPriority.start();
    try {
      // 同优先级按输出价升序；低优先级在健康的高优先级渠道在场时不应被碰到
      ChannelRegistry registry = registry(
          List.of(binding("cheap", url(cheap), 1, 1.0),
              binding("pricey", url(pricey), 1, 9.0),
              binding("lowp", url(lowPriority), 2, 0.1)),
          props(tempDir));
      ChatController controller = new ChatController(registry, new UsageJournal(props(tempDir)));

      assertEquals("cheap", channelOf(controller), "同优先级：便宜的先上");
      assertEquals("cheap", channelOf(controller));
      assertEquals(0, lowPriorityCalls.get(), "高优先级健康时低优先级不应被消耗");

      MockHttpServletResponse response = new MockHttpServletResponse();
      controller.chat("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}", null, response);
      assertEquals(200, response.getStatus());
      assertNotNull(response.getHeader("X-Router-Cost-Usd"), "价目全知时成本必须当场可见");
      assertTrue(Double.parseDouble(response.getHeader("X-Router-Cost-Usd")) > 0);
    } finally {
      cheap.stop(0);
      pricey.stop(0);
      lowPriority.stop(0);
    }
  }

  @Test
  void upgradePath_missingKeyDisabledAtAssemblyAndUpstreamFixReturnsZeroConfig() throws Exception {
    // A. 装配期：key 环境变量缺失的渠道直接禁用，路由不崩（半配置渠道比没渠道危险）
    ChannelProperties props = props(tempDir);
    ChannelProperties.Channel missing = channelConfig("missing", "http://127.0.0.1:1/v1",
        "MODEL_ROUTER_TEST_KEY_DEFINITELY_UNSET", null);
    ChannelProperties.Channel missing2 = channelConfig("missing2", "http://127.0.0.1:1/v1",
        "MODEL_ROUTER_TEST_KEY_DEFINITELY_UNSET_2", null);
    props.setChannels(List.of(missing, missing2));
    ChannelRegistry assembled = new ChannelRegistry(props);
    assertEquals(0, assembled.statuses().size(), "缺 key 渠道装配期即禁用");
    ChatController empty = new ChatController(assembled, new UsageJournal(props(tempDir)));
    MockHttpServletResponse response = new MockHttpServletResponse();
    empty.chat("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}", null, response);
    assertEquals(503, response.getStatus(), "全部渠道缺失应 503 而不是崩");
    assertTrue(response.getContentAsString().contains("no available channel"));

    // B. 升级路径：上游 key 无效（401 确定性失败）→ 上游开通（改桩返回 200）→ 零配置改动回归轮转
    AtomicBoolean entitled = new AtomicBoolean(false);
    HttpServer newvendor = server(exchange -> {
      if (entitled.get()) {
        respond(exchange, 200, OK);
      } else {
        respond(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
      }
    });
    HttpServer backup = okServer();
    newvendor.start();
    backup.start();
    try {
      ChannelRegistry registry = registry(
          List.of(binding("newvendor", url(newvendor), 1), binding("backup", url(backup), 2)), props(tempDir));
      ChatController controller = new ChatController(registry, new UsageJournal(props(tempDir)));

      assertEquals("backup", channelOf(controller), "401 确定性失败：换道不熔断");
      assertTrue(available(registry, "newvendor"), "确定性失败不打开熔断器");

      entitled.set(true);
      assertEquals("newvendor", channelOf(controller), "上游开通后零配置改动纳入轮转");
      assertEquals("newvendor", channelOf(controller), "且稳定服务");
    } finally {
      newvendor.stop(0);
      backup.stop(0);
    }
  }

  private static String channelOf(ChatController controller) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}", null, response);
    assertEquals(200, response.getStatus());
    return response.getHeader("X-Router-Channel");
  }

  private static boolean available(ChannelRegistry registry, String id) {
    return registry.statuses().stream()
        .filter(status -> status.id().equals(id))
        .findFirst().orElseThrow().available();
  }

  private static ChannelRegistry registry(List<ChannelRegistry.ChannelBinding> bindings,
                                          ChannelProperties props) {
    return new ChannelRegistry(bindings, 2, java.time.Duration.ofMillis(250), props);
  }

  // ===== helpers（与 ChannelRecoveryTest 同套打法）=====

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private static ChannelProperties props(Path dir) {
    ChannelProperties props = new ChannelProperties();
    props.setUsagePath(dir.resolve("usage.jsonl").toString());
    props.setProbeModels(false);
    return props;
  }

  private static ChannelRegistry.ChannelBinding binding(String id, String baseUrl, int priority) {
    return new ChannelRegistry.ChannelBinding(channel(id, baseUrl, priority, null), priority);
  }

  private static ChannelRegistry.ChannelBinding binding(String id, String baseUrl, int priority, Double priceOut) {
    return new ChannelRegistry.ChannelBinding(channel(id, baseUrl, priority, priceOut), priority);
  }

  private static LlmChannel channel(String id, String baseUrl, int priority, Double priceOut) {
    return new HttpChannel(channelConfig(id, baseUrl, "MODEL_ROUTER_TEST_KEY_NOT_SET", priceOut));
  }

  private static ChannelProperties.Channel channelConfig(String id, String baseUrl, String apiKeyEnv, Double priceOut) {
    ChannelProperties.Channel config = new ChannelProperties.Channel();
    config.setId(id);
    config.setBaseUrl(baseUrl);
    config.setApiKeyEnv(apiKeyEnv);
    config.setModels(List.of("m1"));
    config.setTimeout(java.time.Duration.ofSeconds(10));
    if (priceOut != null) {
      config.setPricePerMTokIn(priceOut);
      config.setPricePerMTokOut(priceOut);
    }
    return config;
  }

  private static HttpServer okServer() throws Exception {
    return server(exchange -> respond(exchange, 200, OK));
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
