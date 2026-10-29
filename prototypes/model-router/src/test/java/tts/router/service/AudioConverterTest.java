package tts.router.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tts.router.config.RouterProperties;
import tts.router.provider.FakeEngine;
import tts.router.provider.SpeechCommand;
import tts.router.web.RouterException;

class AudioConverterTest {

  @Test
  void convertsWavToMp3WhenFfmpegPresent() {
    RouterProperties properties = new RouterProperties();
    assumeTrue(Files.isExecutable(Path.of(properties.getAudio().getFfmpegPath())),
        "ffmpeg not installed, skipping");
    byte[] wav = new FakeEngine().synthesize(new SpeechCommand("m", "v", "转码测试", 1.0f)).data();

    byte[] mp3 = new AudioConverter(properties).toMp3(wav);

    assertThat(mp3).isNotEmpty();
    boolean id3 = mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3';
    boolean frameSync = (mp3[0] & 0xFF) == 0xFF;
    assertThat(id3 || frameSync).as("mp3 should start with ID3 tag or MPEG frame sync").isTrue();
  }

  @Test
  void failsCleanlyWhenFfmpegMissing() {
    RouterProperties properties = new RouterProperties();
    properties.getAudio().setFfmpegPath("Z:/definitely/not/here/ffmpeg.exe");

    assertThatThrownBy(() -> new AudioConverter(properties).toMp3(new byte[44]))
        .isInstanceOf(RouterException.class)
        .hasMessageContaining("ffmpeg not found");
  }
}
