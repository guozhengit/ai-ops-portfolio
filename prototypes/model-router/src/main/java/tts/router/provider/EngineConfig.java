package tts.router.provider;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tts.router.config.RouterProperties;

@Configuration
public class EngineConfig {

  @Bean
  List<Binding> engineBindings(RouterProperties properties) {
    return properties.getEngines().stream()
        .map(engine -> new Binding(new HttpEngine(engine), engine.getPriority()))
        .toList();
  }

  @Bean
  @Profile("dev")
  Binding devBrokenEngine() {
    return new Binding(new BrokenEngine(), 1);
  }

  @Bean
  @Profile("dev")
  Binding devFakeEngine() {
    return new Binding(new FakeEngine(), 2);
  }
}
