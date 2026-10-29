package model.router.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import model.router.config.ChannelProperties.Channel;

/**
 * 启动期能力探测：拿 GET {base-url}/models 的实际权益，校准配置里声明的模型清单。
 *
 * <p>为什么需要它：中转渠道的可用模型是「跟着 key 走的」，同一个 base-url 换一把 key，
 * 权益可能完全不相交。声明里的模型如果 key 根本没买，请求期会稳定拿到
 * 404 model_not_found——那是确定性失败，不熔断、只换渠道，白白多跳一次。
 * 启动期校准掉，请求期就不用再为配置错误买单。
 *
 * <p>边界：探测无结论（404/401/超时/返回格式不认识）时返回 null，调用方保留声明清单。
 * 探测失败只说明「这条探测路径不通」，不说明渠道不可用。
 */
public final class CapabilityProbe {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CapabilityProbe() {
  }

  /** 该 key 实际能用的模型 id；null = 探测无结论。 */
  public static List<String> probeModels(Channel config, Duration timeout) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(config.baseUrl() + "/models"))
          .timeout(timeout)
          .header("Authorization", "Bearer " + config.apiKey())
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      JsonNode data = MAPPER.readTree(response.body()).path("data");
      if (!data.isArray()) {
        return null;
      }
      List<String> ids = new ArrayList<>();
      for (JsonNode item : data) {
        String id = item.path("id").asText("");
        if (!id.isBlank()) {
          ids.add(id);
        }
      }
      return ids.isEmpty() ? null : ids;
    } catch (Exception e) {
      return null;
    }
  }

  /** 纯函数：声明 ∩ 实际权益，保持声明顺序。 */
  public static List<String> supported(List<String> declared, List<String> actual) {
    return declared.stream().filter(actual::contains).toList();
  }
}
