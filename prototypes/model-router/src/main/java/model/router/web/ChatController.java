package model.router.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import model.router.provider.ChannelException;
import model.router.provider.ChannelRegistry;
import model.router.provider.LlmChannel;
import model.router.provider.SseUsageStream;
import model.router.service.UsageJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/chat/completions —— OpenAI 兼容入口。
 * 路由循环语义：按选路顺序逐渠道尝试；确定性失败（402/400）与瞬态失败（503/429/超时）
 * 都换下一个渠道，但只有瞬态失败计入熔断；全部失败返回 502，body 里带逐渠道失败原因。
 */
@RestController
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ChannelRegistry registry;
  private final UsageJournal journal;

  public ChatController(ChannelRegistry registry, UsageJournal journal) {
    this.registry = registry;
    this.journal = journal;
  }

  @PostMapping(value = "/v1/chat/completions")
  public void chat(@RequestBody String rawBody, HttpServletRequest request,
                   HttpServletResponse response) throws Exception {
    String model;
    boolean stream;
    try {
      JsonNode body = MAPPER.readTree(rawBody);
      model = body.path("model").asText("");
      stream = body.path("stream").asBoolean(false);
    } catch (Exception e) {
      response.setStatus(400);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(
          "{\"error\":{\"message\":\"invalid JSON body\",\"type\":\"invalid_request_error\"}}");
      return;
    }
    if (model.isBlank()) {
      response.setStatus(400);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(
          "{\"error\":{\"message\":\"model is required\",\"type\":\"invalid_request_error\"}}");
      return;
    }

    List<ChannelRegistry.ChannelTarget> targets = registry.candidates(model);
    if (targets.isEmpty()) {
      response.setStatus(503);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(
          "{\"error\":{\"message\":\"no available channel for model " + model
              + "\",\"type\":\"service_unavailable\"}}");
      return;
    }

    LinkedHashMap<String, String> failures = new LinkedHashMap<>();
    for (ChannelRegistry.ChannelTarget target : targets) {
      LlmChannel channel = target.channel();
      try {
        String body = withModel(rawBody, target.upstreamModel());
        if (stream) {
          streamToResponse(channel, body, model, target.upstreamModel(), response);
        } else {
          LlmChannel.ChatResult result = channel.chat(body);
          registry.recordSuccess(channel.id());
          journal.record(channel, model, target.upstreamModel(), result.promptTokens(),
              result.completionTokens(), result.latencyMs(), false);
          response.setStatus(200);
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          response.setHeader("X-Router-Channel", channel.id());
          response.getWriter().write(result.body());
        }
        return;
      } catch (ChannelException e) {
        registry.recordFailure(channel.id(), e.transientFailure());
        failures.put(channel.id(), e.getMessage());
        log.warn("channel={} failed (transient={}): {}", channel.id(), e.transientFailure(),
            e.getMessage());
      }
    }
    response.setStatus(502);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(MAPPER.writeValueAsString(Map.of(
        "error", Map.of(
            "message", "all channels failed for model " + model,
            "type", "router_all_channels_failed",
            "channels", failures))));
  }

  /** 把请求体里的 model 字段改写为该渠道的上游模型名（别名降级的核心动作）。 */
  private static String withModel(String rawBody, String upstreamModel) {
    try {
      JsonNode body = MAPPER.readTree(rawBody);
      ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("model", upstreamModel);
      return MAPPER.writeValueAsString(body);
    } catch (Exception e) {
      return rawBody;
    }
  }

  private void streamToResponse(LlmChannel channel, String rawBody, String requestedModel,
                                String upstreamModel, HttpServletResponse response) throws Exception {
    long startedAt = System.currentTimeMillis();
    LlmChannel.StreamedChat streamed = channel.chatStream(rawBody);
    registry.recordSuccess(channel.id());
    // 流式没有响应头可用：token 数在最后一个 SSE 帧里，边透传边抄表，传完再落账
    SseUsageStream tracked = new SseUsageStream(streamed.stream());
    response.setStatus(200);
    response.setContentType(streamed.contentType());
    response.setHeader("X-Router-Channel", streamed.channel());
    try (InputStream in = tracked; OutputStream out = response.getOutputStream()) {
      in.transferTo(out);
      out.flush();
    }
    journal.record(channel, requestedModel, upstreamModel, tracked.promptTokens(),
        tracked.completionTokens(), System.currentTimeMillis() - startedAt, true);
    log.info("stream recorded channel={} model={} promptTokens={} completionTokens={}",
        channel.id(), upstreamModel, tracked.promptTokens(), tracked.completionTokens());
  }

  // /v1/models 由 SpeechController 提供（TTS 时代遗留的聚合端点）；
  // LLM 渠道的模型清单聚合进它，属于路由策略统一那一步的活
}
