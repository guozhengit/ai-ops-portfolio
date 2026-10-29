package model.router.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import model.router.config.ChannelProperties.Channel;
import org.junit.jupiter.api.Test;

/**
 * 状态码 → 瞬态/确定性的分类矩阵，走真实 HTTP（本地 HttpServer），不是靠 mock 猜语义。
 *
 * <p>真实依据见 docs/channel-verification-miku-20261029.md：
 * 404 model_not_found（key 没权益）必须当成确定性失败——熔断一个「配置写错了」的渠道毫无意义；
 * 503/502（上游临时故障）才算瞬态，要计数开闸。
 */
class HttpChannelStatusTest {

  @Test
  void modelNotFound404IsDeterministic() {
    ChannelException e = callReturning(404,
        "{\"error\":{\"message\":\"Model \\\"gpt-5.4-mini\\\" is not supported by any "
            + "configured account in this group\",\"type\":\"model_not_found\"}}");
    assertFalse(e.transientFailure(), "404 是确定性失败，不该进熔断");
    assertTrue(e.getMessage().contains("model_not_found"), "失败原因要带上游原文：" + e.getMessage());
  }

  @Test
  void serverErrorsAreTransient() {
    for (int status : List.of(500, 502, 503, 429, 408)) {
      ChannelException e = callReturning(status, "{\"error\":{\"message\":\"upstream\"}}");
      assertTrue(e.transientFailure(), status + " 应该是瞬态失败（值得冷却）");
    }
  }

  @Test
  void deterministicClientErrorsDoNotOpenBreaker() {
    for (int status : List.of(400, 401, 402, 404)) {
      ChannelException e = callReturning(status, "{\"error\":{\"message\":\"nope\"}}");
      assertFalse(e.transientFailure(), status + " 应该是确定性失败");
    }
  }

  @Test
  void usageIsParsedIncludingReasoningTokens() {
    String body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{"
        + "\"prompt_tokens\":37,\"completion_tokens\":22,"
        + "\"completion_tokens_details\":{\"reasoning_tokens\":19}}}";
    HttpChannel channel = channelReturning(200, body);
    LlmChannel.ChatResult result = channel.chat("{\"model\":\"m1\"}");
    assertEquals(37, result.promptTokens());
    assertEquals(22, result.completionTokens(), "completion_tokens 含推理 token，照实记");
  }

  private static ChannelException callReturning(int status, String body) {
    HttpChannel channel = channelReturning(status, body);
    return assertThrows(ChannelException.class, () -> channel.chat("{\"model\":\"m1\"}"));
  }

  private static HttpChannel channelReturning(int status, String body) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      server.createContext("/v1/chat/completions", exchange -> {
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
      });
      server.start();
      Channel config = new Channel();
      config.setId("stub");
      config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      config.setApiKeyEnv("MODEL_ROUTER_TEST_KEY_NOT_SET");
      config.setModels(List.of("m1"));
      config.setTimeout(Duration.ofSeconds(10));
      return new HttpChannel(config);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
