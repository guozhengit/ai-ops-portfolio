package model.router.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import model.router.config.RouterProperties;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

  public static final String API_KEY_ATTRIBUTE = "ttsApiKey";

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

  private final List<String> validKeys;

  public ApiKeyAuthFilter(RouterProperties properties) {
    this.validKeys = properties.getApiKeys();
    if (validKeys.isEmpty()) {
      log.warn("no tts.api-keys configured - /v1 endpoints are open, do not expose this on the public internet");
    }
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (validKeys.isEmpty()) {
      request.setAttribute(API_KEY_ATTRIBUTE, "anonymous");
      chain.doFilter(request, response);
      return;
    }

    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      reject(response, 401, "authentication_error", "No API key provided. Set the Authorization header.");
      return;
    }
    String key = header.substring(7).trim();
    if (!matches(key)) {
      reject(response, 401, "invalid_api_key", "Invalid API key.");
      return;
    }
    request.setAttribute(API_KEY_ATTRIBUTE, key);
    chain.doFilter(request, response);
  }

  /**
   * SHA-256 后恒时比较，且遍历全部 key 不提前退出，
   * 避免用时差异泄露「匹配到第几个 key」这类信息。
   */
  private boolean matches(String provided) {
    byte[] providedHash = sha256(provided);
    boolean matched = false;
    for (String key : validKeys) {
      if (MessageDigest.isEqual(providedHash, sha256(key))) {
        matched = true;
      }
    }
    return matched;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(
        "{\"error\":{\"message\":\"" + message + "\",\"type\":\"authentication_error\",\"param\":null,\"code\":\""
            + code + "\"}}");
  }
}
