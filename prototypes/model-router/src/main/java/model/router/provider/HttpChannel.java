package model.router.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import model.router.config.ChannelProperties.Channel;

/** HTTP 实现：POST {base-url}/chat/completions，Bearer 鉴权，SSE 直接透传字节流。 */
public class HttpChannel implements LlmChannel {

  private final Channel config;
  private final HttpClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpChannel(Channel config) {
    this.config = config;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public String id() {
    return config.id();
  }

  @Override
  public List<String> models() {
    return config.models();
  }

  @Override
  public boolean supports(String model) {
    return config.models().contains(model);
  }

  @Override
  public double pricePerMTokIn() {
    return config.pricePerMTokIn();
  }

  @Override
  public double pricePerMTokOut() {
    return config.pricePerMTokOut();
  }

  @Override
  public ChatResult chat(String rawBody) {
    long start = System.currentTimeMillis();
    HttpResponse<String> response = send(rawBody, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw classify(response.statusCode(), response.body());
    }
    Integer in = null;
    Integer out = null;
    try {
      JsonNode usage = mapper.readTree(response.body()).path("usage");
      if (usage.isObject()) {
        in = usage.path("prompt_tokens").asInt(0);
        out = usage.path("completion_tokens").asInt(0);
      }
    } catch (Exception ignore) {
      // usage 解析失败不影响转发响应本身
    }
    return new ChatResult(config.id(), response.body(), in, out,
        System.currentTimeMillis() - start);
  }

  @Override
  public StreamedChat chatStream(String rawBody) {
    HttpResponse<InputStream> response = send(rawBody, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      String body = readFully(response.body());
      throw classify(response.statusCode(), body);
    }
    String contentType = response.headers().firstValue("Content-Type").orElse("text/event-stream");
    return new StreamedChat(config.id(), response.body(), contentType);
  }

  private <T> HttpResponse<T> send(String rawBody, HttpResponse.BodyHandler<T> handler) {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.baseUrl() + "/chat/completions"))
          .timeout(config.timeout())
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + config.apiKey())
          .POST(HttpRequest.BodyPublishers.ofString(rawBody))
          .build();
      return client.send(request, handler);
    } catch (java.net.http.HttpTimeoutException e) {
      throw new ChannelException("channel " + config.id() + " timeout", e, true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChannelException("channel " + config.id() + " interrupted", e, true);
    } catch (Exception e) {
      // 连接层失败（拒连/DNS/TLS）按瞬态处理：渠道大概率只是暂时不可达
      throw new ChannelException("channel " + config.id() + " call failed: " + e.getMessage(), e, true);
    }
  }

  /** 5xx/429/408 是渠道侧临时状况（值得冷却）；其余 4xx（402 余额、400 参数、401 key 错）是确定性失败。 */
  private ChannelException classify(int status, String body) {
    boolean transientFailure = status >= 500 || status == 429 || status == 408;
    String snippet = body == null ? "" : body.substring(0, Math.min(180, body.length()));
    return new ChannelException("channel " + config.id() + " returned status " + status + " | " + snippet,
        transientFailure);
  }

  private static String readFully(InputStream stream) {
    try (stream) {
      return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }
}
