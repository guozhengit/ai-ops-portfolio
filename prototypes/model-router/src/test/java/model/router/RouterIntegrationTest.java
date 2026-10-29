package model.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import model.router.config.RouterProperties;

/**
 * 把 README 里的手工 curl 场景固化为集成测试：dev 假引擎 + 内存 H2，不依赖 GPU 与外网。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
class RouterIntegrationTest {

  @Autowired
  TestRestTemplate http;

  @Autowired
  RouterProperties properties;

  private HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("sk-test-key");
    return headers;
  }

  private HttpHeaders jsonAuth() {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private String codeOf(ResponseEntity<Map> res) {
    Map<String, Object> error = (Map<String, Object>) res.getBody().get("error");
    return (String) error.get("code");
  }

  @Test
  void speechEndpointsRejectMissingAndInvalidKeys() {
    ResponseEntity<Map> noAuth = http.getForEntity("/v1/models", Map.class);
    assertThat(noAuth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(codeOf(noAuth)).isEqualTo("authentication_error");

    HttpHeaders bad = new HttpHeaders();
    bad.setBearerAuth("sk-wrong");
    ResponseEntity<Map> invalid = http.exchange("/v1/models", HttpMethod.GET,
        new HttpEntity<>(bad), Map.class);
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(codeOf(invalid)).isEqualTo("invalid_api_key");
  }

  @Test
  void modelsListDevEngines() {
    ResponseEntity<Map> res = http.exchange("/v1/models", HttpMethod.GET,
        new HttpEntity<>(authHeaders()), Map.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

    List<?> data = (List<?>) res.getBody().get("data");
    assertThat(data).isNotEmpty();
    assertThat(data.stream().map(m -> ((Map) m).get("id").toString()))
        .contains("dev-kokoro", "dev-edge");
  }

  @Test
  void speechFallsBackToHealthyEngineThenCaches() {
    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro",
        "voice", "zf_xiaoxiao",
        "input", "回退与缓存 " + UUID.randomUUID());

    ResponseEntity<byte[]> first = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), byte[].class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    // dev-broken（priority 1）必然失败，应降级到 dev-fake
    assertThat(first.getHeaders().getFirst("X-Engine")).isEqualTo("dev-fake");
    assertThat(first.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
    assertThat(first.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("audio/wav"));
    assertThat(new String(first.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");

    ResponseEntity<byte[]> second = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), byte[].class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
    assertThat(second.getHeaders().getFirst("X-Engine")).isEqualTo("cache");
    assertThat(second.getBody()).isEqualTo(first.getBody());
  }

  @Test
  void rejectsUnsupportedResponseFormat() {
    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro", "voice", "zf_xiaoxiao",
        "input", "不支持 ogg " + UUID.randomUUID(), "response_format", "ogg");

    ResponseEntity<Map> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), Map.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(codeOf(res)).isEqualTo("unsupported_response_format");
  }

  @Test
  void rejectsUnknownModel() {
    Map<String, Object> payload = Map.of(
        "model", "no-such-model", "voice", "zf_xiaoxiao",
        "input", "未知模型 " + UUID.randomUUID());

    ResponseEntity<Map> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), Map.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(codeOf(res)).isEqualTo("model_not_found");
  }

  @Test
  void splitsLongTextAndConcatenatesAudio() {
    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro", "voice", "zf_xiaoxiao",
        // UUID 前缀保证跨测试运行不撞缓存
        "input", UUID.randomUUID() + "长" .repeat(5000));

    ResponseEntity<byte[]> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), byte[].class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getHeaders().getFirst("X-Engine")).isEqualTo("dev-fake");
    byte[] body = res.getBody();
    assertThat(body).isNotNull();
    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(body)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    assertThat(buf.getInt(40)).isEqualTo(body.length - 44);
    // 假引擎单片最长 1200ms ≈ 57600 字节 PCM，拼接成功才会显著超过单片上限
    assertThat(buf.getInt(40)).isGreaterThan(60_000);
  }

  @Test
  void convertsToMp3WhenFfmpegPresent() {
    assumeTrue(Files.isExecutable(Path.of(properties.getAudio().getFfmpegPath())),
        "ffmpeg not installed, skipping mp3 conversion test");

    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro", "voice", "zf_xiaoxiao",
        "input", "mp3 转码 " + UUID.randomUUID(), "response_format", "mp3");

    ResponseEntity<byte[]> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, jsonAuth()), byte[].class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("audio/mpeg"));
    byte[] body = res.getBody();
    assertThat(body).isNotEmpty();
    boolean id3 = body[0] == 'I' && body[1] == 'D' && body[2] == '3';
    boolean frameSync = (body[0] & 0xFF) == 0xFF;
    assertThat(id3 || frameSync).as("mp3 should start with ID3 tag or MPEG frame sync").isTrue();
  }

  @Test
  void statusEndpointIsPublicAndComplete() {
    ResponseEntity<Map> res = http.getForEntity("/ui/api/status", Map.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<?, ?> body = res.getBody();
    assertThat(body.get("authRequired")).isEqualTo(true);
    assertThat((List<?>) body.get("engines")).isNotEmpty();
    assertThat((List<?>) body.get("models")).isNotEmpty();
    assertThat(body.get("usage")).isNotNull();
    assertThat(body.get("cache")).isNotNull();
    assertThat(body.get("quota")).isNotNull();
  }
}
