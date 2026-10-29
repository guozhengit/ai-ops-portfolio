package model.router.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import model.router.config.RouterProperties;
import model.router.provider.EngineRegistry;
import model.router.provider.SpeechCommand;
import model.router.service.SpeechService;
import model.router.service.UsageLedger;

@RestController
@RequestMapping("/v1")
public class SpeechController {

  private final SpeechService speechService;
  private final EngineRegistry registry;
  private final UsageLedger ledger;
  private final RouterProperties properties;

  public SpeechController(SpeechService speechService, EngineRegistry registry, UsageLedger ledger,
      RouterProperties properties) {
    this.speechService = speechService;
    this.registry = registry;
    this.ledger = ledger;
    this.properties = properties;
  }

  @GetMapping("/models")
  public Map<String, Object> models() {
    List<Map<String, String>> data = registry.modelOwners().entrySet().stream()
        .map(entry -> Map.of(
            "id", entry.getKey(),
            "object", "model",
            "owned_by", entry.getValue()))
        .toList();
    return Map.of("object", "list", "data", data);
  }

  @PostMapping("/audio/speech")
  public ResponseEntity<byte[]> speech(@Valid @RequestBody SpeechRequest request, HttpServletRequest http) {
    String apiKey = (String) http.getAttribute(ApiKeyAuthFilter.API_KEY_ATTRIBUTE);

    String format = request.responseFormatOrDefault();
    if (!List.of("wav", "mp3").contains(format)) {
      throw new RouterException(HttpStatus.BAD_REQUEST, "unsupported_response_format",
          "Supported response_format values are wav and mp3; got '" + request.responseFormat() + "'.");
    }

    long used = ledger.charsUsedToday(apiKey);
    long limit = properties.getQuota().getDailyChars();
    if (properties.getQuota().isEnabled() && used + request.input().length() > limit) {
      throw new RouterException(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota",
          "Daily character quota exhausted: used " + used + " of " + limit + ".");
    }

    SpeechCommand command = new SpeechCommand(
        request.model(), request.voice(), request.input(), request.speedOrDefault());

    SpeechService.Outcome outcome = speechService.synthesize(apiKey, command, format);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(outcome.audio().contentType()))
        .header("X-Cache", outcome.cacheHit() ? "HIT" : "MISS")
        .header("X-Engine", outcome.audio().engineId())
        .header("X-Usage-Chars-Today", String.valueOf(used + request.input().length()))
        .body(outcome.audio().data());
  }
}
