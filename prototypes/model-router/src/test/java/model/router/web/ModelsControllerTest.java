package model.router.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import model.router.config.ChannelProperties;
import model.router.config.RouterProperties;
import model.router.provider.Binding;
import model.router.provider.ChannelRegistry;
import model.router.provider.EngineRegistry;
import model.router.provider.FakeEngine;
import model.router.provider.StubLlmChannel;
import model.router.service.ModelCatalog;
import model.router.provider.ChannelRegistryTestSupport;
import org.junit.jupiter.api.Test;

/**
 * /v1/models 的三路合并：TTS 引擎 + LLM 渠道 + 别名。
 * 别名必须在清单里——请求方是照着这张表挑模型名的，挑不到就会绕过路由直连上游。
 */
class ModelsControllerTest {

  @SuppressWarnings("unchecked")
  @Test
  void modelsMergeEnginesChannelsAndAliases() {
    EngineRegistry engines = new EngineRegistry(List.of(new Binding(new FakeEngine(), 1)),
        new RouterProperties());
    ChannelRegistry channels = ChannelRegistryTestSupport.registryOf(2,
        new StubLlmChannel("miku", List.of("deepseek-v4.1-flash")));
    ChannelProperties props = new ChannelProperties();
    props.setAliases(Map.of("gpt-5.4-mini", List.of("miku:deepseek-v4.1-flash", "ark:doubao")));

    Map<String, Object> body = new ModelsController(new ModelCatalog(engines, channels, props)).models();
    List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

    assertEquals("list", body.get("object"));
    Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
    data.forEach(item -> byId.put((String) item.get("id"), item));

    assertTrue(byId.containsKey("dev-edge"), "TTS 引擎模型要在：" + byId.keySet());
    assertTrue(byId.containsKey("deepseek-v4.1-flash"), "LLM 渠道模型要在：" + byId.keySet());
    assertTrue(byId.containsKey("gpt-5.4-mini"), "别名入口要在，否则请求方会绕过路由：" + byId.keySet());

    assertEquals("tts", byId.get("dev-edge").get("kind"));
    assertEquals("llm", byId.get("deepseek-v4.1-flash").get("kind"));
    assertEquals("miku", byId.get("deepseek-v4.1-flash").get("owned_by"));
    // 别名链上 ark 这个渠道不存在：owners 只该剩 miku，不能把死链也报出去
    assertEquals(List.of("miku"), byId.get("gpt-5.4-mini").get("owners"));
    assertEquals("alias", byId.get("gpt-5.4-mini").get("kind"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void aliasWithNoLiveChannelIsNotAdvertised() {
    EngineRegistry engines = new EngineRegistry(List.of(), new RouterProperties());
    ChannelRegistry channels = ChannelRegistryTestSupport.registryOf(2,
        new StubLlmChannel("miku", List.of("deepseek-v4.1-flash")));
    ChannelProperties props = new ChannelProperties();
    props.setAliases(Map.of("gpt-5.4-mini", List.of("ghost:gpt-5.4-mini")));

    Map<String, Object> body = new ModelsController(new ModelCatalog(engines, channels, props)).models();
    List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

    assertFalse(data.stream().anyMatch(item -> "gpt-5.4-mini".equals(item.get("id"))),
        "降级链上游全不在的别名不该出现在清单里");
    assertTrue(data.stream().anyMatch(item -> "deepseek-v4.1-flash".equals(item.get("id"))));
  }
}
