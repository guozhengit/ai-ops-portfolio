package model.router.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tts")
public class RouterProperties {

  private final Cache cache = new Cache();
  private final Quota quota = new Quota();
  private final Health health = new Health();
  private final Audio audio = new Audio();
  private final Split split = new Split();
  private final Budget budget = new Budget();
  private List<String> apiKeys = new ArrayList<>();
  private List<Engine> engines = new ArrayList<>();

  /** 超过单片上限的长文本切分策略；硬顶防止一次请求打出成百上千个引擎调用。 */
  public static class Split {
    private boolean enabled = true;
    private long maxTotalChars = 100_000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getMaxTotalChars() {
      return maxTotalChars;
    }

    public void setMaxTotalChars(long maxTotalChars) {
      this.maxTotalChars = maxTotalChars;
    }
  }

  public static class Audio {
    private String ffmpegPath = "D:/Program Files/ffmpeg/bin/ffmpeg.exe";
    private String mp3Bitrate = "128k";
    private Duration timeout = Duration.ofSeconds(30);

    public String getFfmpegPath() {
      return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
      this.ffmpegPath = ffmpegPath;
    }

    public String getMp3Bitrate() {
      return mp3Bitrate;
    }

    public void setMp3Bitrate(String mp3Bitrate) {
      this.mp3Bitrate = mp3Bitrate;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }

  public static class Cache {
    private boolean enabled = true;
    private String directory = ".cache/audio";
    private long maxBytes = 512L * 1024 * 1024;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getDirectory() {
      return directory;
    }

    public void setDirectory(String directory) {
      this.directory = directory;
    }

    public long getMaxBytes() {
      return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
      this.maxBytes = maxBytes;
    }
  }

  public static class Quota {
    private boolean enabled = true;
    private long dailyChars = 200_000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getDailyChars() {
      return dailyChars;
    }

    public void setDailyChars(long dailyChars) {
      this.dailyChars = dailyChars;
    }
  }

  public static class Health {
    private int failureThreshold = 2;
    private Duration cooldown = Duration.ofSeconds(60);

    public int getFailureThreshold() {
      return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
      this.failureThreshold = failureThreshold;
    }

    public Duration getCooldown() {
      return cooldown;
    }

    public void setCooldown(Duration cooldown) {
      this.cooldown = cooldown;
    }
  }

  public static class Engine {
    private String id;
    private String baseUrl;
    private List<String> models = new ArrayList<>();
    private int priority = 100;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxInputChars = 4096;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public List<String> getModels() {
      return models;
    }

    public void setModels(List<String> models) {
      this.models = models;
    }

    public int getPriority() {
      return priority;
    }

    public void setPriority(int priority) {
      this.priority = priority;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }

    public int getMaxInputChars() {
      return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
      this.maxInputChars = maxInputChars;
    }
  }

  /** V2-D2.3：LLM 日级预算硬止损。默认关闭；账本层 null 纪律不动，护栏层未知成本上界估记。 */
  public static class Budget {
    private boolean enabled = false;
    private double dailyUsd = 0;
    /** 前置预留：发请求前按「当日已用 + 预留」判断，避免本笔调用把当日账打穿。 */
    private double reservePerCallUsd = 0;
    /** 注册表里没有任何已知价目时，未知成本上界估记的兜底单价（$/MTok）。 */
    private double estimateFallbackPerMTokOut = 0;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public double getDailyUsd() {
      return dailyUsd;
    }

    public void setDailyUsd(double dailyUsd) {
      this.dailyUsd = dailyUsd;
    }

    public double getReservePerCallUsd() {
      return reservePerCallUsd;
    }

    public void setReservePerCallUsd(double reservePerCallUsd) {
      this.reservePerCallUsd = reservePerCallUsd;
    }

    public double getEstimateFallbackPerMTokOut() {
      return estimateFallbackPerMTokOut;
    }

    public void setEstimateFallbackPerMTokOut(double estimateFallbackPerMTokOut) {
      this.estimateFallbackPerMTokOut = estimateFallbackPerMTokOut;
    }
  }

  public Cache getCache() {
    return cache;
  }

  public Split getSplit() {
    return split;
  }

  public Quota getQuota() {
    return quota;
  }

  public Budget getBudget() {
    return budget;
  }

  public Health getHealth() {
    return health;
  }

  public Audio getAudio() {
    return audio;
  }

  public List<String> getApiKeys() {
    return apiKeys;
  }

  public void setApiKeys(List<String> apiKeys) {
    this.apiKeys = apiKeys;
  }

  public List<Engine> getEngines() {
    return engines;
  }

  public void setEngines(List<Engine> engines) {
    this.engines = engines;
  }
}
