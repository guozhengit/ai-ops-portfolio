package model.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import model.router.config.RouterProperties;

@SpringBootApplication
@EnableConfigurationProperties(RouterProperties.class)
public class ModelRouterApplication {

  public static void main(String[] args) {
    SpringApplication.run(ModelRouterApplication.class, args);
  }
}
