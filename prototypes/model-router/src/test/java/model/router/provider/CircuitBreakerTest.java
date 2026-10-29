package model.router.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 熔断语义：瞬态失败计数开闸、冷却半开、确定性失败不计入。 */
class CircuitBreakerTest {

  @Test
  void opensAfterThresholdAndRecoversAfterCooldown() {
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(60));
    Instant now = Instant.now();
    breaker.recordFailure("ch", true, now);
    assertTrue(breaker.isAvailable("ch", now), "1 次瞬态失败不应开闸");

    breaker.recordFailure("ch", true, now);
    assertFalse(breaker.isAvailable("ch", now), "达到阈值应开闸");

    Instant afterCooldown = now.plus(Duration.ofSeconds(61));
    assertTrue(breaker.isAvailable("ch", afterCooldown), "冷却到点应半开恢复");
  }

  @Test
  void deterministicFailuresDoNotTrip() {
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(60));
    Instant now = Instant.now();
    breaker.recordFailure("ch", false, now);
    breaker.recordFailure("ch", false, now);
    breaker.recordFailure("ch", false, now);
    assertTrue(breaker.isAvailable("ch", now), "确定性失败（402/400）不应触发熔断");
  }

  @Test
  void successResetsCounter() {
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(60));
    Instant now = Instant.now();
    breaker.recordFailure("ch", true, now);
    breaker.recordSuccess("ch");
    breaker.recordFailure("ch", true, now);
    assertTrue(breaker.isAvailable("ch", now), "成功清零后单次失败不应开闸");
  }
}
