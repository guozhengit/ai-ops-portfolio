package model.router.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE 透传流的「旁路抄表」：字节原样往下游走，顺手把帧里的 usage 抄下来。
 *
 * <p>为什么需要它：流式响应的 token 数不在响应头里，而在最后一个 chunk。
 * 实测 MikuAPI 的 usage 就长在 {@code finish_reason: stop} 那一帧的 usage 字段上
 * （加不加 stream_options.include_usage 都给）。不解析就只能记 token 未知、成本未知，
 * 那流式调用在成本账里就是一笔糊涂账。
 *
 * <p>纪律：解析失败一律静默跳过——抄表出错不能影响字节透传本身。
 */
public final class SseUsageStream extends InputStream {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final InputStream delegate;
  private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
  private Integer promptTokens;
  private Integer completionTokens;

  public SseUsageStream(InputStream delegate) {
    this.delegate = delegate;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n == -1 ? -1 : one[0] & 0xff;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int n = delegate.read(b, off, len);
    if (n > 0) {
      pending.write(b, off, n);
      drainCompleteLines();
    }
    return n;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  public Integer promptTokens() {
    return promptTokens;
  }

  public Integer completionTokens() {
    return completionTokens;
  }

  /** 只解析已经收完整的行，跨 chunk 的半行留在 pending 里等下一次。 */
  private void drainCompleteLines() {
    byte[] buf = pending.toByteArray();
    int lastNewline = -1;
    for (int i = 0; i < buf.length; i++) {
      if (buf[i] == '\n') {
        handleLine(new String(buf, lastNewline + 1, i - lastNewline - 1, StandardCharsets.UTF_8));
        lastNewline = i;
      }
    }
    pending.reset();
    if (lastNewline + 1 < buf.length) {
      pending.write(buf, lastNewline + 1, buf.length - lastNewline - 1);
    }
  }

  private void handleLine(String line) {
    if (!line.startsWith("data:")) {
      return;
    }
    String payload = line.substring("data:".length()).trim();
    if (payload.isEmpty() || "[DONE]".equals(payload)) {
      return;
    }
    try {
      JsonNode usage = MAPPER.readTree(payload).path("usage");
      if (!usage.isObject() || usage.isNull()) {
        return;
      }
      if (usage.hasNonNull("prompt_tokens")) {
        promptTokens = usage.get("prompt_tokens").asInt();
      }
      if (usage.hasNonNull("completion_tokens")) {
        completionTokens = usage.get("completion_tokens").asInt();
      }
    } catch (Exception ignore) {
      // 抄表失败不影响透传
    }
  }
}
