package model.router.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM 渠道轴配置（router.*），与冻结的 tts.* 互相独立。 */
@ConfigurationProperties(prefix = "router")
public class ChannelProperties {

  private final Health health = new Health();
  private List<Channel> channels = new ArrayList<>();
  /** usage JSONL 落盘路径（相对工作目录）。 */
  private String usagePath = "data/usage.jsonl";
  /**
   * 同能力别名表：key=请求方眼中的模型名，value=「渠道id:上游模型名」列表。
   * 例：gpt-5.4-mini → [miku:gpt-5.4-mini, ark:doubao-seed-2.1-turbo]
   * ——miku 挂掉时自动换 doubao 顶上，请求方只认一个名字。
   */
  private Map<String, List<String>> aliases = new LinkedHashMap<>();

  public Map<String, List<String>> getAliases() {
    return aliases;
  }

  public void setAliases(Map<String, List<String>> aliases) {
    this.aliases = aliases;
  }

  public List<Channel> getChannels() {
    return channels;
  }

  public void setChannels(List<Channel> channels) {
    this.channels = channels;
  }

  public Health getHealth() {
    return health;
  }

  public String getUsagePath() {
    return usagePath;
  }

  public void setUsagePath(String usagePath) {
    this.usagePath = usagePath;
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

  public static class Channel {
    private String id;
    private String baseUrl;
    /** 从环境变量读取 key，配置文件永远不落明文（.env 治理纪律）。 */
    private String apiKeyEnv;
    private List<String> models = new ArrayList<>();
    /** 越小越优先；同优先级按输出单价升序。 */
    private int priority = 100;
    private double pricePerMTokIn;
    private double pricePerMTokOut;
    private Duration timeout = Duration.ofSeconds(90);

    public String resolveApiKey() {
      if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
        return "";
      }
      String value = System.getenv(apiKeyEnv);
      return value == null ? "" : value;
    }

    /** 解析后的明文 key，仅 HttpChannel 运行时使用；任何日志/落盘都不得引用。 */
    public String apiKey() {
      return resolveApiKey();
    }

    public String id() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String baseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String apiKeyEnv() {
      return apiKeyEnv;
    }

    public void setApiKeyEnv(String apiKeyEnv) {
      this.apiKeyEnv = apiKeyEnv;
    }

    public List<String> models() {
      return models;
    }

    public void setModels(List<String> models) {
      this.models = models;
    }

    public int priority() {
      return priority;
    }

    public void setPriority(int priority) {
      this.priority = priority;
    }

    public double pricePerMTokIn() {
      return pricePerMTokIn;
    }

    public void setPricePerMTokIn(double pricePerMTokIn) {
      this.pricePerMTokIn = pricePerMTokIn;
    }

    public double pricePerMTokOut() {
      return pricePerMTokOut;
    }

    public void setPricePerMTokOut(double pricePerMTokOut) {
      this.pricePerMTokOut = pricePerMTokOut;
    }

    public Duration timeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }
}
