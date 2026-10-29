package model.router.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
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

  public void record(LlmChannel channel, String requestedModel, String upstreamModel,
                     Integer promptTokens, Integer completionTokens, long latencyMs, boolean stream) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", Instant.now().toString());
    row.put("channel", channel.id());
    row.put("model", requestedModel);
    row.put("upstreamModel", upstreamModel);
    row.put("promptTokens", promptTokens == null ? 0 : promptTokens);
    row.put("completionTokens", completionTokens == null ? 0 : completionTokens);
    double costUsd = (promptTokens == null ? 0 : promptTokens) / 1_000_000.0 * channel.pricePerMTokIn()
        + (completionTokens == null ? 0 : completionTokens) / 1_000_000.0 * channel.pricePerMTokOut();
    row.put("costUsd", Math.round(costUsd * 1_000_000.0) / 1_000_000.0);
    row.put("pricePerMTokIn", channel.pricePerMTokIn());
    row.put("pricePerMTokOut", channel.pricePerMTokOut());
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
  }
}
