package tts.router.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tts.router.config.RouterProperties;

@Configuration
public class WebConfig {

  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(RouterProperties properties) {
    FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new ApiKeyAuthFilter(properties));
    registration.addUrlPatterns("/v1/*");
    registration.setOrder(1);
    return registration;
  }
}
