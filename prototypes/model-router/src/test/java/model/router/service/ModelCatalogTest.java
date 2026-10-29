package model.router.service;

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
import model.router.provider.ChannelRegistryTestSupport;
import org.junit.jupiter.api.Test;

/**
 * ModelCatalog 是 /v1/models 与 /ui/api/status 的唯一清单来源——
 * 之前两边各算各的，控制台那份只有 TTS 模型，和真实入口对不上。
 */
class ModelCatalogTest {

  private static ModelCatalog catalog(ChannelProperties props) {
    EngineRegistry engines = new EngineRegistry(List.of(new Binding(new FakeEngine(), 1)),
        new RouterProperties());
    ChannelRegistry channels = ChannelRegistryTestSupport.registryOf(2,
        new StubLlmChannel("miku", List.of("deepseek-v4.1-flash")));
    return new ModelCatalog(engines, channels, props);
  }

  @Test
  void catalogContainsEnginesChannelsAndAliases() {
    ChannelProperties props = new ChannelProperties();
    props.setAliases(Map.of("gpt-5.4-mini", List.of("miku:deepseek-v4.1-flash")));

    List<ModelCatalog.CatalogEntry> entries = catalog(props).entries();
    assertTrue(entries.stream().anyMatch(item -> item.id().equals("dev-edge")
        && item.kind().equals("tts")));
    assertTrue(entries.stream().anyMatch(item -> item.id().equals("deepseek-v4.1-flash")
        && item.kind().equals("llm")));
    assertTrue(entries.stream().anyMatch(item -> item.id().equals("gpt-5.4-mini")
        && item.kind().equals("alias")));
  }

  @Test
  void chatCapableExcludesSpeechOnlyModels() {
    // 控制台的试调下拉如果混进 TTS 模型，用户点下去必然 503
    ModelCatalog catalog = catalog(new ChannelProperties());
    List<String> ids = catalog.chatCapable().stream().map(ModelCatalog.CatalogEntry::id).toList();

    assertTrue(ids.contains("deepseek-v4.1-flash"), ids.toString());
    assertFalse(ids.contains("dev-edge"), "语音模型不该出现在对话模型下拉里：" + ids);
    assertFalse(ids.contains("dev-kokoro"), ids.toString());
  }
}
