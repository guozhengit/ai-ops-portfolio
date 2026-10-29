package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;
import model.router.config.ChannelProperties;
import model.router.provider.ChannelRegistry;
import model.router.provider.StubLlmChannel;
import model.router.provider.ChannelException;
import model.router.service.UsageJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 显式锁渠道：调试 / 灰度 / 同一个请求换供应商比效果时，路由策略必须能让位给人。
 * 两种写法：头 X-Router-Pin，或模型名前缀 <渠道id>:<模型名>。
 */
class ChannelPinTest {

  @TempDir
  java.nio.file.Path tempDir;

  private ChannelProperties props() {
    ChannelProperties props = new ChannelProperties();
    props.setUsagePath(tempDir.resolve("usage.jsonl").toString());
    return props;
  }

  private static MockHttpServletRequest requestWithPin(String pin) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (pin != null) {
      request.addHeader("X-Router-Pin", pin);
    }
    return request;
  }

  @Test
  void pinHeaderOverridesHigherPriorityChannel() throws Exception {
    // 正常情况下 m1 会先走 priority 1 的 primary
    StubLlmChannel primary = new StubLlmChannel("primary", List.of("m1"));
    StubLlmChannel secondary = new StubLlmChannel("secondary", List.of("m1"));
    ChannelRegistry registry = new ChannelRegistry(
        List.of(new ChannelRegistry.ChannelBinding(primary, 1),
            new ChannelRegistry.ChannelBinding(secondary, 2)),
        2, Duration.ofSeconds(60), props());
    ChatController controller = new ChatController(registry, new UsageJournal(props()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\"}", requestWithPin("secondary"), response);

    assertEquals(200, response.getStatus());
    assertEquals("secondary", response.getHeader("X-Router-Channel"), "锁了就该走锁定的那个");
  }

  @Test
  void modelPrefixPinsChannelAndIsStrippedFromJournal() throws Exception {
    StubLlmChannel ark = new StubLlmChannel("ark", List.of("doubao-seed-2.1-turbo"));
    StubLlmChannel mimo = new StubLlmChannel("mimo", List.of("doubao-seed-2.1-turbo"));
    ChannelRegistry registry = new ChannelRegistry(
        List.of(new ChannelRegistry.ChannelBinding(mimo, 1),
            new ChannelRegistry.ChannelBinding(ark, 2)),
        2, Duration.ofSeconds(60), props());
    ChannelProperties props = props();
    ChatController controller = new ChatController(registry, new UsageJournal(props));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"ark:doubao-seed-2.1-turbo\"}", requestWithPin(null), response);

    assertEquals(200, response.getStatus());
    assertEquals("ark", response.getHeader("X-Router-Channel"), "前缀应锁定 ark，哪怕 mimo 优先级更高");

    String line = java.nio.file.Files.readString(tempDir.resolve("usage.jsonl")).trim();
    assertTrue(line.contains("\"model\":\"doubao-seed-2.1-turbo\""),
        "前缀只是寻址语法，账本里不能留着它：" + line);
  }

  @Test
  void unknownPinReturns400() throws Exception {
    ChannelRegistry registry = new ChannelRegistry(
        List.of(new ChannelRegistry.ChannelBinding(new StubLlmChannel("ark", List.of("m1")), 1)),
        2, Duration.ofSeconds(60), props());
    ChatController controller = new ChatController(registry, new UsageJournal(props()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\"}", requestWithPin("ghost"), response);

    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("unknown channel 'ghost'"),
        response.getContentAsString());
  }

  @Test
  void pinnedChannelInCooldownIsStillAttempted() throws Exception {
    // 冷却中的渠道，正常选路会被跳过；但显式锁定表达的是「我就是要打它」
    StubLlmChannel always503 = new StubLlmChannel("flaky", List.of("m1")) {
      @Override
      public model.router.provider.LlmChannel.ChatResult chat(String rawBody) {
        throw new ChannelException("flaky returned status 503", true);
      }
    };
    StubLlmChannel backup = new StubLlmChannel("backup", List.of("m1"));
    ChannelRegistry registry = new ChannelRegistry(
        List.of(new ChannelRegistry.ChannelBinding(always503, 1),
            new ChannelRegistry.ChannelBinding(backup, 2)),
        1, Duration.ofSeconds(60), props());
    ChatController controller = new ChatController(registry, new UsageJournal(props()));

    // 先打两次让它开闸（阈值 1）
    controller.chat("{\"model\":\"m1\"}", requestWithPin(null), new MockHttpServletResponse());
    controller.chat("{\"model\":\"m1\"}", requestWithPin(null), new MockHttpServletResponse());
    var status = registry.statuses().stream().filter(s -> s.id().equals("flaky")).findFirst()
        .orElseThrow();
    assertEquals(false, status.available(), "flaky 应已进入冷却");
    assertNotNull(status.cooldownUntil());

    // 锁定它：即使冷却也要真的发一次，失败就如实报失败
    MockHttpServletResponse pinned = new MockHttpServletResponse();
    controller.chat("{\"model\":\"m1\"}", requestWithPin("flaky"), pinned);
    assertEquals(502, pinned.getStatus());
    assertTrue(pinned.getContentAsString().contains("flaky"), pinned.getContentAsString());
  }
}
