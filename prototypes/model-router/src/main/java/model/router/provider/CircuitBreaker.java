package model.router.provider;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按渠道 id 的熔断状态机（语义与 EngineRegistry 里的实现一致，抽出共享给 LLM 渠道轴）。
 * 连续 transientFailure 达到阈值 → 打开冷却期；期内不可用；冷却到点自动半开恢复；
 * 任一次成功即清零。确定性失败不进熔断——换个渠道就好，没必要冷却。
 */
public class CircuitBreaker {

  public record BreakerStatus(boolean available, Instant cooldownUntil) {}

  private final int failureThreshold;
  private final java.time.Duration cooldown;
  private final Map<String, Integer> failures = new ConcurrentHashMap<>();
  private final Map<String, Instant> openUntil = new ConcurrentHashMap<>();

  public CircuitBreaker(int failureThreshold, java.time.Duration cooldown) {
    this.failureThreshold = failureThreshold;
    this.cooldown = cooldown;
  }

  public boolean isAvailable(String id, Instant now) {
    Instant until = openUntil.get(id);
    if (until == null) {
      return true;
    }
    if (until.isAfter(now)) {
      return false;
    }
    openUntil.remove(id, until);
    return true;
  }

  public void recordSuccess(String id) {
    failures.remove(id);
    openUntil.remove(id);
  }

  public void recordFailure(String id, boolean transientFailure, Instant now) {
    if (!transientFailure) {
      return;
    }
    int count = failures.merge(id, 1, Integer::sum);
    if (count >= failureThreshold) {
      Instant until = now.plus(cooldown);
      openUntil.put(id, until);
      failures.remove(id);
    }
  }

  public BreakerStatus status(String id, Instant now) {
    return new BreakerStatus(isAvailable(id, now), openUntil.get(id));
  }
}
