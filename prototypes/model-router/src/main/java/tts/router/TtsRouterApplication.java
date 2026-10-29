package tts.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tts.router.config.RouterProperties;

@SpringBootApplication
@EnableConfigurationProperties(RouterProperties.class)
public class TtsRouterApplication {

  public static void main(String[] args) {
    SpringApplication.run(TtsRouterApplication.class, args);
  }
}
