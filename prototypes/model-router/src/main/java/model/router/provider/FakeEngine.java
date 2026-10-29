package model.router.provider;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

/** dev profile 用的假引擎：不依赖 GPU/外网，生成一段正弦波 WAV，用来验证路由链路本身。 */
public class FakeEngine implements TtsEngine {

  @Override
  public String id() {
    return "dev-fake";
  }

  @Override
  public Set<String> models() {
    return Set.of("dev-kokoro", "dev-edge");
  }

  @Override
  public int maxInputChars() {
    return 4096;
  }

  @Override
  public boolean supports(String model) {
    return models().contains(model);
  }

  @Override
  public AudioResult synthesize(SpeechCommand command) {
    int sampleRate = 24000;
    int durationMs = Math.min(1200, 200 + command.text().length() * 10);
    int samples = sampleRate * durationMs / 1000;
    byte[] pcm = new byte[samples * 2];
    for (int i = 0; i < samples; i++) {
      short value = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000);
      pcm[i * 2] = (byte) (value & 0xff);
      pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
    }

    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes());
    header.putInt(36 + pcm.length);
    header.put("WAVE".getBytes());
    header.put("fmt ".getBytes());
    header.putInt(16);
    header.putShort((short) 1);
    header.putShort((short) 1);
    header.putInt(sampleRate);
    header.putInt(sampleRate * 2);
    header.putShort((short) 2);
    header.putShort((short) 16);
    header.put("data".getBytes());
    header.putInt(pcm.length);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(header.array(), 0, header.array().length);
    out.write(pcm, 0, pcm.length);
    return new AudioResult(out.toByteArray(), "audio/wav", id());
  }
}
