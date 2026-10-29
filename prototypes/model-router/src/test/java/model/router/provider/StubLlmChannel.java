package model.router.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 测试替身：一个只会报名字、不会真发请求的渠道。 */
public class StubLlmChannel implements LlmChannel {

  private final String id;
  private final List<String> models;

  public StubLlmChannel(String id, List<String> models) {
    this.id = id;
    this.models = models;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public List<String> models() {
    return models;
  }

  @Override
  public boolean supports(String model) {
    return models.contains(model);
  }

  @Override
  public Double pricePerMTokIn() {
    return null;
  }

  @Override
  public Double pricePerMTokOut() {
    return null;
  }

  @Override
  public ChatResult chat(String rawBody) {
    return new ChatResult(id, "{}", null, null, 0);
  }

  @Override
  public StreamedChat chatStream(String rawBody) {
    InputStream stream = new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    return new StreamedChat(id, stream, "text/event-stream");
  }
}
