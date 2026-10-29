package model.router.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import model.router.config.RouterProperties.Engine;

/**
 * 引擎侧车契约：POST {base_url}/synthesize，JSON 入参，返回音频字节。
 * Kokoro / Edge-TTS 等本地引擎都实现这一个契约即可接入。
 */
public class HttpEngine implements TtsEngine {

  private final Engine config;
  private final HttpClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpEngine(Engine config) {
    this.config = config;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public String id() {
    return config.getId();
  }

  @Override
  public Set<String> models() {
    return Set.copyOf(config.getModels());
  }

  @Override
  public int maxInputChars() {
    return config.getMaxInputChars();
  }

  @Override
  public boolean supports(String model) {
    return config.getModels().contains(model);
  }

  @Override
  public AudioResult synthesize(SpeechCommand command) {
    try {
      String payload = mapper.writeValueAsString(Map.of(
          "text", command.text(),
          "voice", command.voice(),
          "speed", command.speed(),
          "format", "wav"));

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.getBaseUrl() + "/synthesize"))
          .timeout(config.getTimeout())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload))
          .build();

      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        int status = response.statusCode();
        // 5xx/429 是引擎侧的临时状况，值得冷却等待恢复；4xx 是确定性失败，换个引擎就好，不该把引擎打进冷却
        boolean transientFailure = status >= 500 || status == 429;
        throw new EngineException("engine " + id() + " returned status " + status, transientFailure);
      }
      String contentType = response.headers().firstValue("Content-Type").orElse("audio/wav");
      return new AudioResult(response.body(), contentType, id());
    } catch (EngineException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EngineException("engine " + id() + " interrupted", e, true);
    } catch (Exception e) {
      throw new EngineException("engine " + id() + " call failed: " + e.getMessage(), e, true);
    }
  }
}
