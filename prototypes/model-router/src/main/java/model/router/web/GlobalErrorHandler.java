package model.router.web;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

  @ExceptionHandler(RouterException.class)
  public ResponseEntity<Map<String, Object>> handleRouter(RouterException e) {
    return ResponseEntity.status(e.status()).body(error(e.getMessage(), e.code()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(field -> field.getField() + " " + field.getDefaultMessage())
        .reduce((a, b) -> a + "; " + b)
        .orElse("invalid request");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(message, "invalid_request"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
    // 请求体不是合法 JSON（如编码错误）是客户端问题，不该算 500
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("malformed request body", "invalid_request"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
    String ref = UUID.randomUUID().toString().substring(0, 8);
    // 内部异常的类名和消息不回给客户端，只给一个能在日志里检索的引用号
    log.error("unhandled error ref={}", ref, e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(error("internal error, ref=" + ref, "internal_error"));
  }

  private Map<String, Object> error(String message, String code) {
    return Map.of("error", Map.of(
        "message", message,
        "type", "insufficient_quota".equals(code) ? "rate_limit_error" : "invalid_request_error",
        "code", code));
  }
}
