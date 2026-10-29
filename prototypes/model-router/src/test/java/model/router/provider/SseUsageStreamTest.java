package model.router.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** SSE 旁路抄表：usage 长在 finish 帧上；跨 chunk 的半行要能拼回来；[DONE] 不带 usage。 */
class SseUsageStreamTest {

  @Test
  void usageIsCapturedFromFinishFrame() throws Exception {
    String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}],\"usage\":null}\n\n"
        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":37,\"completion_tokens\":22}}\n\n"
        + "data: [DONE]\n\n";
    SseUsageStream stream = new SseUsageStream(bytes(sse));
    stream.transferTo(java.io.OutputStream.nullOutputStream());
    assertEquals(37, stream.promptTokens());
    assertEquals(22, stream.completionTokens());
  }

  @Test
  void usageIsCapturedEvenWhenSplitAcrossChunks() throws Exception {
    String sse = "data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}\n\n";
    InputStream source = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        // 每次只吐 8 字节：验证半行不会被丢
        return super.read(b, off, Math.min(8, len));
      }
    };
    SseUsageStream stream = new SseUsageStream(source);
    stream.transferTo(java.io.OutputStream.nullOutputStream());
    assertEquals(11, stream.promptTokens());
    assertEquals(22, stream.completionTokens());
  }

  @Test
  void noUsageFrameLeavesTokensUnknown() throws Exception {
    SseUsageStream stream = new SseUsageStream(bytes("data: {\"choices\":[]}\n\ndata: [DONE]\n\n"));
    stream.transferTo(java.io.OutputStream.nullOutputStream());
    assertNull(stream.promptTokens(), "上游没给 usage 就记未知，不猜");
    assertNull(stream.completionTokens());
  }

  private static InputStream bytes(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }
}
