package tts.router.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import tts.router.web.RouterException;

/**
 * 把多个标准 PCM WAV 拼成一个：校验采样参数一致后按序拼接 PCM，重写 RIFF/data 长度。
 * 引擎契约只承诺 WAV，不承诺分片间参数一致，不一致时报 500 而不是硬拼出杂音。
 */
final class WavMerger {

  private WavMerger() {}

  static byte[] merge(List<byte[]> wavs) {
    if (wavs.size() == 1) {
      return wavs.get(0);
    }
    List<Pcm> chunks = wavs.stream().map(WavMerger::parse).toList();
    Pcm first = chunks.get(0);
    for (int i = 1; i < chunks.size(); i++) {
      Pcm next = chunks.get(i);
      if (next.channels() != first.channels() || next.sampleRate() != first.sampleRate()
          || next.bitsPerSample() != first.bitsPerSample()) {
        throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "wav_concat_failed",
            "chunk " + i + " format mismatch: " + next.sampleRate() + "Hz/" + next.channels()
                + "ch/" + next.bitsPerSample() + "bit vs " + first.sampleRate() + "Hz/"
                + first.channels() + "ch/" + first.bitsPerSample() + "bit");
      }
    }
    int total = chunks.stream().mapToInt(chunk -> chunk.data().length).sum();

    byte[] out = Arrays.copyOf(header(first.channels(), first.sampleRate(), first.bitsPerSample(), total),
        44 + total);
    int offset = 44;
    for (Pcm chunk : chunks) {
      System.arraycopy(chunk.data(), 0, out, offset, chunk.data().length);
      offset += chunk.data().length;
    }
    return out;
  }

  private record Pcm(short channels, int sampleRate, short bitsPerSample, byte[] data) {}

  private static Pcm parse(byte[] wav) {
    if (wav.length < 44 || !hasTag(wav, 0, "RIFF") || !hasTag(wav, 8, "WAVE")) {
      throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "wav_concat_failed",
          "not a RIFF/WAVE stream (" + wav.length + " bytes)");
    }
    ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
    Short channels = null;
    Integer sampleRate = null;
    Short bits = null;
    byte[] data = null;
    int pos = 12;
    while (pos + 8 <= wav.length) {
      String id = new String(wav, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
      int size = buf.getInt(pos + 4);
      if (size < 0) {
        throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "wav_concat_failed",
            "corrupt WAV chunk size at offset " + pos);
      }
      int body = pos + 8;
      if (id.equals("fmt ")) {
        short audioFormat = buf.getShort(body);
        if (audioFormat != 1) {
          throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "wav_concat_failed",
              "only PCM WAV is supported, got format " + audioFormat);
        }
        channels = buf.getShort(body + 2);
        sampleRate = buf.getInt(body + 4);
        bits = buf.getShort(body + 14);
      } else if (id.equals("data")) {
        int len = Math.min(size, wav.length - body);
        data = Arrays.copyOfRange(wav, body, body + len);
      }
      pos = body + size + (size % 2);
    }
    if (channels == null || sampleRate == null || bits == null || data == null) {
      throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "wav_concat_failed",
          "incomplete WAV: missing fmt or data chunk");
    }
    return new Pcm(channels, sampleRate, bits, data);
  }

  private static byte[] header(short channels, int sampleRate, short bits, int dataLen) {
    ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    h.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    h.putInt(36 + dataLen);
    h.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    h.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    h.putInt(16);
    h.putShort((short) 1);
    h.putShort(channels);
    h.putInt(sampleRate);
    h.putInt(sampleRate * channels * bits / 8);
    h.putShort((short) (channels * bits / 8));
    h.putShort(bits);
    h.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    h.putInt(dataLen);
    return h.array();
  }

  private static boolean hasTag(byte[] wav, int offset, String tag) {
    return wav.length >= offset + 4 && tag.equals(new String(wav, offset, 4,
        java.nio.charset.StandardCharsets.US_ASCII));
  }
}
