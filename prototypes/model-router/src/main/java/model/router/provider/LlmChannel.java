package model.router.provider;

import java.io.InputStream;

/** 渠道抽象：一个 OpenAI 兼容的 LLM 服务方（一个 key + 一个 base_url + 模型清单 + 价格）。 */
public interface LlmChannel {

  String id();

  /** 该渠道可服务的模型名集合。 */
  java.util.List<String> models();

  boolean supports(String model);

  /** 每百万输入 token 单价（USD），成本账的一等公民。 */
  double pricePerMTokIn();

  /** 每百万输出 token 单价（USD）。 */
  double pricePerMTokOut();

  /** 非流式调用：转发原始请求体，返回响应 JSON 原文与 usage。 */
  ChatResult chat(String rawBody);

  /** 流式调用：SSE 透传。 */
  StreamedChat chatStream(String rawBody);

  record ChatResult(String channel, String body, Integer promptTokens, Integer completionTokens,
                    long latencyMs) {}

  record StreamedChat(String channel, InputStream stream, String contentType) {}
}
