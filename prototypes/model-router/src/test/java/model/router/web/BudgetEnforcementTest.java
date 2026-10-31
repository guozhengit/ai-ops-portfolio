package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.HttpChannel;
import model.router.provider.LlmChannel;
import model.router.service.BudgetGuard;
import model.router.service.UsageJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 预算硬止损端到端演练（V2-D2.3 验收门）：真实 HTTP 渠道 + 前置 429 + 留痕。
 *
 * <p>两个场景对应预注册决策 #3 的两层：已知成本超限（价目齐全渠道）与
 * 未知成本上界估记超限（无价目渠道——账本层依然记 null，护栏层靠估记拦住）。
 */
class BudgetEnforcementTest {

  @TempDir
  Path tempDir;

  private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
      + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

  @Test
  void knownCostOverBudgetGetsDeniedWith429AndAuditTrail() throws Exception {
    HttpServer priced = server(exchange -> respond(exchange, 200, OK));
    priced.start();
    try {
      // 输出价 $2/MTok：1+1 tokens = $4e-6/笔。日预算 $4.5e-6 + 本笔预留 $1e-6：
      // 第 1 笔（0+1e-6 ≤ 4.5e-6）过；第 2 笔（4e-6+1e-6 > 4.5e-6）拒
      ChannelProperties props = props(tempDir);
      LlmChannel channel = channel("priced", "http://127.0.0.1:" + priced.getAddress().getPort() + "/v1", 2.0);
      ChannelRegistry registry = new ChannelRegistry(
          List.of(new ChannelRegistry.ChannelBinding(channel, 1)), 2, java.time.Duration.ofSeconds(60), props);
      BudgetGuard guard = new BudgetGuard(true, 0.0000045, 0.000001, 0,
          tempDir.resolve("budget-events.jsonl"), java.time.Clock.systemUTC());
      ChatController controller = new ChatController(registry, new UsageJournal(props), guard);

      MockHttpServletResponse first = chat(controller);
      assertEquals(200, first.getStatus(), "第 1 笔放行");
      assertEquals(4.0e-6, Double.parseDouble(first.getHeader("X-Router-Cost-Usd")), 1e-9,
          "成本当场回给调用方");

      MockHttpServletResponse second = chat(controller);
      assertEquals(429, second.getStatus(), "第 2 笔超日预算应前置拒绝");
      assertEquals("denied", second.getHeader("X-Router-Budget"));
      assertTrue(second.getContentAsString().contains("insufficient_budget"),
          "拒绝体必须带 insufficient_budget 类型");
      assertTrue(Files.readString(tempDir.resolve("budget-events.jsonl")).contains("budget_denied"),
          "拒绝必须落审计");
    } finally {
      priced.stop(0);
    }
  }

  @Test
  void unknownCostIsEstimatedForBudgetButStaysNullInLedger() throws Exception {
    HttpServer priceless = server(exchange -> respond(exchange, 200, OK));
    priceless.start();
    try {
      // 渠道无价目 → 账本 costUsd=null；护栏用兜底价 $1/MTok 估记：2 tokens = $2e-6/笔，
      // 日预算 $1.5e-6 → 第 2 笔拒。两层各说各的真话（预注册决策 #3）。
      ChannelProperties props = props(tempDir);
      LlmChannel channel = channel("priceless", "http://127.0.0.1:" + priceless.getAddress().getPort() + "/v1", null);
      ChannelRegistry registry = new ChannelRegistry(
          List.of(new ChannelRegistry.ChannelBinding(channel, 1)), 2, java.time.Duration.ofSeconds(60), props);
      BudgetGuard guard = new BudgetGuard(true, 0.0000015, 0.0, 1.0,
          tempDir.resolve("budget-events.jsonl"), java.time.Clock.systemUTC());
      ChatController controller = new ChatController(registry, new UsageJournal(props), guard);

      MockHttpServletResponse first = chat(controller);
      assertEquals(200, first.getStatus());
      assertEquals("unknown", first.getHeader("X-Router-Cost-Usd"), "账本层：未知就是 unknown，不是 0");

      MockHttpServletResponse second = chat(controller);
      assertEquals(429, second.getStatus(), "估记进护栏后同样应触发硬止损");

      String ledger = Files.readString(tempDir.resolve("usage.jsonl"));
      assertTrue(ledger.contains("\"costKnown\":false"), "账本层纪律不动：未知记 null/false");
      String events = Files.readString(tempDir.resolve("budget-events.jsonl"));
      assertTrue(events.contains("cost_estimated"), "护栏层：估记必须留痕可审");
    } finally {
      priceless.stop(0);
    }
  }

  private static MockHttpServletResponse chat(ChatController controller) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}", null, response);
    return response;
  }

  private static ChannelProperties props(Path tempDir) {
    ChannelProperties props = new ChannelProperties();
    props.setUsagePath(tempDir.resolve("usage.jsonl").toString());
    return props;
  }

  private static LlmChannel channel(String id, String baseUrl, Double priceOut) {
    ChannelProperties.Channel config = new ChannelProperties.Channel();
    config.setId(id);
    config.setBaseUrl(baseUrl);
    config.setApiKeyEnv("MODEL_ROUTER_TEST_KEY_NOT_SET");
    config.setModels(List.of("m1"));
    config.setTimeout(java.time.Duration.ofSeconds(10));
    config.setPricePerMTokIn(priceOut);
    config.setPricePerMTokOut(priceOut);
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
