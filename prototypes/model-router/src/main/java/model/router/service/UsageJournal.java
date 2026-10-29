package model.router.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import model.router.provider.LlmChannel;

/**
 * 成本账本：每次成功调用追加一行 JSONL（ts/channel/model/tokens/cost/latency）。
 * 与 self-healing-agent 的 incident journal 同一套落盘纪律——数据有出处，文章里每个数字都能对到文件。
 */
@Component
public class UsageJournal {

  private static final Logger log = LoggerFactory.getLogger(UsageJournal.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path path;

  public UsageJournal(model.router.config.ChannelProperties properties) {
    this.path = Path.of(properties.getUsagePath());
  }

  /**
   * 记账并返回本次成本（null = 价目未知）。
   * 返回它是为了让调用方能把成本写进响应头——控制台要当场看到花了多少钱。
   */
  public Double record(LlmChannel channel, String requestedModel, String upstreamModel,
                     Integer promptTokens, Integer completionTokens, long latencyMs, boolean stream) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", Instant.now().toString());
    row.put("channel", channel.id());
    row.put("model", requestedModel);
    row.put("upstreamModel", upstreamModel);
    Integer prompt = promptTokens == null ? 0 : promptTokens;
    Integer completion = completionTokens == null ? 0 : completionTokens;
    row.put("promptTokens", prompt);
    row.put("completionTokens", completion);
    Double priceIn = channel.pricePerMTokIn();
    Double priceOut = channel.pricePerMTokOut();
    // 价目未知或 token 数未知 → 成本记 null，绝不记 0：记 0 就是把「不知道」写成「免费」
    boolean costKnown = priceIn != null && priceOut != null && promptTokens != null
        && completionTokens != null;
    double cost = 0;
    if (costKnown) {
      cost = prompt / 1_000_000.0 * priceIn + completion / 1_000_000.0 * priceOut;
      row.put("costUsd", Math.round(cost * 1_000_000.0) / 1_000_000.0);
    } else {
      row.put("costUsd", null);
    }
    row.put("costKnown", costKnown);
    row.put("pricePerMTokIn", priceIn);
    row.put("pricePerMTokOut", priceOut);
    row.put("latencyMs", latencyMs);
    row.put("stream", stream);
    try {
      Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
      Files.writeString(path, MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      // 账本写失败不能影响主请求，只告警
      log.warn("usage journal write failed: {}", e.toString());
    }
    return costKnown ? Math.round(cost * 1_000_000.0) / 1_000_000.0 : null;
  }

  /** 某个维度（全局或单渠道）的用量汇总。 */
  public record UsageSummary(long calls, long promptTokens, long completionTokens,
                             double knownCostUsd, long unknownCostCalls) {}

  /**
   * 今日用量（按系统时区切天）。控制台看板读它——账本在磁盘上，看板不该另算一套。
   * 价目未知的那几笔单独计数，不混进 knownCostUsd。
   */
  public UsageSummary today() {
    java.time.LocalDate today = java.time.LocalDate.now();
    long calls = 0;
    long prompt = 0;
    long completion = 0;
    double cost = 0;
    long unknown = 0;
    for (Map<String, Object> row : readRows()) {
      Object ts = row.get("ts");
      if (ts instanceof String text) {
        try {
          java.time.Instant instant = java.time.Instant.parse(text);
          if (!java.time.LocalDate.from(instant.atZone(java.time.ZoneId.systemDefault()))
              .equals(today)) {
            continue;
          }
        } catch (Exception ignore) {
          // 时间解析不出来就按今天的算，别丢账
        }
      }
      calls++;
      prompt += asLong(row.get("promptTokens"));
      completion += asLong(row.get("completionTokens"));
      Object costValue = row.get("costUsd");
      if (costValue instanceof Number number) {
        cost += number.doubleValue();
      } else {
        unknown++;
      }
    }
    return new UsageSummary(calls, prompt, completion, Math.round(cost * 1_000_000.0) / 1_000_000.0,
        unknown);
  }

  /** 今日按渠道拆分的用量，供控制台的渠道表展示。 */
  public Map<String, UsageSummary> todayByChannel() {
    Map<String, long[]> acc = new LinkedHashMap<>();
    java.time.LocalDate today = java.time.LocalDate.now();
    for (Map<String, Object> row : readRows()) {
      Object ts = row.get("ts");
      if (ts instanceof String text) {
        try {
          java.time.Instant instant = java.time.Instant.parse(text);
          if (!java.time.LocalDate.from(instant.atZone(java.time.ZoneId.systemDefault()))
              .equals(today)) {
            continue;
          }
        } catch (Exception ignore) {
          // 同上
        }
      }
      String channel = String.valueOf(row.get("channel"));
      long[] bucket = acc.computeIfAbsent(channel, key -> new long[5]);
      bucket[0]++;
      bucket[1] += asLong(row.get("promptTokens"));
      bucket[2] += asLong(row.get("completionTokens"));
      Object costValue = row.get("costUsd");
      if (costValue instanceof Number number) {
        bucket[3] += Math.round(number.doubleValue() * 1_000_000.0);
      } else {
        bucket[4]++;
      }
    }
    Map<String, UsageSummary> result = new LinkedHashMap<>();
    acc.forEach((channel, bucket) -> result.put(channel,
        new UsageSummary(bucket[0], bucket[1], bucket[2], bucket[3] / 1_000_000.0, bucket[4])));
    return result;
  }

  private List<Map<String, Object>> readRows() {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (!Files.exists(path)) {
      return rows;
    }
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        try {
          rows.add(MAPPER.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        } catch (Exception ignore) {
          // 坏行跳过，不拖垮看板
        }
      }
    } catch (IOException e) {
      log.warn("usage journal read failed: {}", e.toString());
    }
    return rows;
  }

  private static long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }
}
