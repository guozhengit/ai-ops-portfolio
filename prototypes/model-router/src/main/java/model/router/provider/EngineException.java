package model.router.provider;

public class EngineException extends RuntimeException {

  /** true = 瞬时故障（网络/超时/5xx/429），计入熔断冷却；false = 确定性失败（4xx），只降级不计熔断。 */
  private final boolean transientFailure;

  public EngineException(String message, boolean transientFailure) {
    super(message);
    this.transientFailure = transientFailure;
  }

  public EngineException(String message, Throwable cause, boolean transientFailure) {
    super(message, cause);
    this.transientFailure = transientFailure;
  }

  public EngineException(String message) {
    this(message, true);
  }

  public boolean isTransientFailure() {
    return transientFailure;
  }
}
