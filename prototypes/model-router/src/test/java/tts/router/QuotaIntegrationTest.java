package tts.router;

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
 * 用极小的日配额验证 429 拦截；独立内存库，避免与其他测试共享计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "tts.quota.daily-chars=10",
    "spring.datasource.url=jdbc:h2:mem:quota_test;DB_CLOSE_DELAY=-1",
    "tts.cache.directory=target/test-cache-quota"
})
@ActiveProfiles({"dev", "test"})
class QuotaIntegrationTest {

  @Autowired
  TestRestTemplate http;

  @Test
  void rejectsWhenDailyQuotaExceeded() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("sk-test-key");
    headers.setContentType(MediaType.APPLICATION_JSON);

    Map<String, Object> payload = Map.of(
        "model", "dev-kokoro", "voice", "zf_xiaoxiao",
        "input", "这段输入超过了十个字符的限额");

    ResponseEntity<Map> res = http.exchange("/v1/audio/speech", HttpMethod.POST,
        new HttpEntity<>(payload, headers), Map.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) res.getBody().get("error");
    assertThat(error.get("code")).isEqualTo("insufficient_quota");
    assertThat(error.get("type")).isEqualTo("rate_limit_error");
  }
}
