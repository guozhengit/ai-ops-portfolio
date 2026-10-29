package model.router.provider;

/** 渠道调用失败。transientFailure=true 表示值得熔断冷却等恢复（5xx/429/超时）；false 表示确定性失败（402 余额、400 参数），换渠道即可。 */
public class ChannelException extends RuntimeException {

  private final boolean transientFailure;

  public ChannelException(String message, boolean transientFailure) {
    super(message);
    this.transientFailure = transientFailure;
  }

  public ChannelException(String message, Throwable cause, boolean transientFailure) {
    super(message, cause);
    this.transientFailure = transientFailure;
  }

  public boolean transientFailure() {
    return transientFailure;
  }
}
