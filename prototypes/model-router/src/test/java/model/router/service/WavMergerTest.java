package model.router.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import model.router.provider.FakeEngine;
import model.router.provider.SpeechCommand;
import model.router.web.RouterException;

class WavMergerTest {

  private static byte[] fakeWav(String text) {
    return new FakeEngine().synthesize(new SpeechCommand("m", "v", text, 1.0f)).data();
  }

  private static int dataLenOf(byte[] wav) {
    return ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40);
  }

  @Test
  void singleInputPassesThrough() {
    byte[] wav = fakeWav("单片");
    assertThat(WavMerger.merge(List.of(wav))).isSameAs(wav);
  }

  @Test
  void mergesSameFormatWavsAndRewritesLengths() {
    byte[] a = fakeWav("第一段文本");
    byte[] b = fakeWav("第二段文本");

    byte[] merged = WavMerger.merge(List.of(a, b));

    ByteBuffer buf = ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(buf.getInt(4)).isEqualTo(merged.length - 8);
    assertThat(buf.getInt(40)).isEqualTo(merged.length - 44);
    assertThat(buf.getInt(40)).isEqualTo(dataLenOf(a) + dataLenOf(b));
  }

  @Test
  void rejectsMismatchedSampleRates() {
    byte[] a = fakeWav("同样");
    byte[] b = fakeWav("不同采样率");
    // 偏移 24 是 fmt 块的 sampleRate（LE int），改成 48k 制造不一致
    b[24] = (byte) 0x80;
    b[25] = (byte) 0xBB;

    assertThatThrownBy(() -> WavMerger.merge(List.of(a, b)))
        .isInstanceOfSatisfying(RouterException.class,
            e -> assertThat(e.code()).isEqualTo("wav_concat_failed"));
  }
}
