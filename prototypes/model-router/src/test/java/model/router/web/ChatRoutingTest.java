package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelException;
import model.router.provider.ChannelRegistry;
import model.router.provider.LlmChannel;
import model.router.provider.ChannelRegistryTestSupport;
import model.router.service.UsageJournal;
import model.router.provider.LlmChannel.ChatResult;
import model.router.provider.LlmChannel.StreamedChat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

/** 路由循环语义：402 落到下一渠道、全失败 502 带逐渠道原因、成功路径记账与成本计算。 */
class ChatRoutingTest {

  @TempDir
  Path tempDir;

  private static final class FakeChannel implements LlmChannel {

    private final String id;
    private final List<String> models;
    private final ChannelException onChat;
    private final String body;
    volatile String lastBody;

    FakeChannel(String id, List<String> models, ChannelException onChat, String body) {
      this.id = id;
      this.models = models;
      this.onChat = onChat;
      this.body = body;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public List<String> models() {
      return models;
    }

    @Override
    public boolean supports(String model) {
      return models.contains(model);
    }

    @Override
    public double pricePerMTokIn() {
      return 1.0;
    }

    @Override
    public double pricePerMTokOut() {
      return 2.0;
    }

    @Override
    public ChatResult chat(String rawBody) {
      lastBody = rawBody;
      if (onChat != null) {
        throw onChat;
      }
      return new ChatResult(id, body, 1000, 500, 42);
    }

    @Override
    public StreamedChat chatStream(String rawBody) {
      lastBody = rawBody;
      return new StreamedChat(id, new ByteArrayInputStream("data: [DONE]\n\n"
          .getBytes(StandardCharsets.UTF_8)), "text/event-stream");
    }
  }

  @Test
  void aliasFallsOverToUpstreamModelOnOtherChannel() throws Exception {
    // 请求 gpt-5.4-mini；miku 直供但持续 503；别名把同一请求换到 ark 的 doubao 模型
    FakeChannel miku = new FakeChannel("miku", List.of("gpt-5.4-mini"),
        new ChannelException("miku returned status 503", true), null);
    FakeChannel ark = new FakeChannel("ark", List.of("doubao-seed-2.1-turbo"), null, "{\"ok\":true}");

    ChannelRegistry registry = new ChannelRegistry(
        List.of(new ChannelRegistry.ChannelBinding(miku, 1),
            new ChannelRegistry.ChannelBinding(ark, 2)),
        2, java.time.Duration.ofSeconds(60), aliasProps());
    ChatController controller = new ChatController(registry,
        new UsageJournal(aliasProps()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"gpt-5.4-mini\",\"messages\":[]}", null, response);

    assertEquals(200, response.getStatus());
    assertEquals("ark", response.getHeader("X-Router-Channel"));
    assertTrue(ark.lastBody != null && ark.lastBody.contains("doubao-seed-2.1-turbo"),
        "请求体应改写为上游模型名：" + ark.lastBody);
  }

  private ChannelProperties aliasProps() {
    ChannelProperties props = properties();
    props.setAliases(Map.of("gpt-5.4-mini", List.of("miku:gpt-5.4-mini", "ark:doubao-seed-2.1-turbo")));
    return props;
  }

  @Test
  void deterministic402FallsThroughToNextChannel() throws Exception {
    FakeChannel broke = new FakeChannel("broke", List.of("m1"),
        new ChannelException("broke returned status 402 | insufficient balance", false), null);
    FakeChannel healthy = new FakeChannel("healthy", List.of("m1"), null,
        "{\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}}");

    ChannelRegistry registry = ChannelRegistryTestSupport.registryOf(2, broke, healthy);
    UsageJournal journal = new UsageJournal(properties());
    ChatController controller = new ChatController(registry, journal);

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\",\"messages\":[]}", null, response);

    assertEquals(200, response.getStatus());
    assertEquals("healthy", response.getHeader("X-Router-Channel"));
    // 402 是确定性失败：broke 渠道不应进熔断，下个请求仍会先试它
    assertTrue(registry.statuses().stream()
        .filter(s -> s.id().equals("broke")).findFirst().orElseThrow().available());
  }

  @Test
  void allChannelsFailedReturns502WithPerChannelReasons() throws Exception {
    FakeChannel a = new FakeChannel("a", List.of("m1"),
        new ChannelException("a returned status 402", false), null);
    FakeChannel b = new FakeChannel("b", List.of("m1"),
        new ChannelException("b timeout", true), null);

    ChannelRegistry registry = ChannelRegistryTestSupport.registryOf(2, a, b);
    ChatController controller = new ChatController(registry,
        new UsageJournal(properties()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\",\"messages\":[]}", null, response);

    assertEquals(502, response.getStatus());
    String body = response.getContentAsString();
    assertTrue(body.contains("\"a\"") && body.contains("402"), "失败原因应带渠道 a");
    assertTrue(body.contains("\"b\"") && body.contains("timeout"), "失败原因应带渠道 b");
    // b 是瞬态失败且阈值 2：两次路由后应开闸
    controller.chat("{\"model\":\"m1\",\"messages\":[]}", null, new MockHttpServletResponse());
    assertFalse(registry.statuses().stream()
        .filter(s -> s.id().equals("b")).findFirst().orElseThrow().available());
  }

  @Test
  void usageJournalWritesCostLine() throws Exception {
    FakeChannel healthy = new FakeChannel("healthy", List.of("m1"), null, "{}");
    ChannelProperties props = properties();
    UsageJournal journal = new UsageJournal(props);
    journal.record(healthy, "m1", "m1", 1_000_000, 500_000, 42, false);

    String line = Files.readString(Path.of(props.getUsagePath())).trim();
    // 1M in × $1 + 0.5M out × $2 = $2.0
    assertTrue(line.contains("\"costUsd\":2.0"), "成本账应入 JSONL：" + line);
    assertTrue(line.contains("\"channel\":\"healthy\""));
  }

  private ChannelProperties properties() {
    ChannelProperties props = new ChannelProperties();
    props.setUsagePath(tempDir.resolve("usage.jsonl").toString());
    return props;
  }
}
