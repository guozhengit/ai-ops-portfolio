package model.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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

/**
 * 切分硬顶：超过 tts.split.max-total-chars 的请求直接 400，不发起任何引擎调用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "tts.split.max-total-chars=50",
    "spring.datasource.url=jdbc:h2:mem:split_test;DB_CLOSE_DELAY=-1",
    "tts.cache.directory=target/test-cache-split"
})
@ActiveProfiles({"dev", "test"})
class SplitIntegrationTest {

  @Autowired
  TestRestTemplate http;

  @Test
  void rejectsInputBeyondSplitCap() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("sk-test-key");
    headers.setContentType(MediaType.APPLICATION_JSON);

    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro", "voice", "zf_xiaoxiao",
        // 必须先超过单片上限（4096）触发切分，再超过硬顶（50）才会 400
        "input", "长" .repeat(4100));

    ResponseEntity<Map> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, headers), Map.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> error = (java.util.Map<String, Object>) res.getBody().get("error");
    assertThat(error.get("code")).isEqualTo("input_too_long");
  }
}
