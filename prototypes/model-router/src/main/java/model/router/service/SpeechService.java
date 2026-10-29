package model.router.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import model.router.config.RouterProperties;
import model.router.provider.AudioResult;
import model.router.provider.EngineException;
import model.router.provider.EngineRegistry;
import model.router.provider.SpeechCommand;
import model.router.provider.TtsEngine;
import model.router.web.RouterException;

@Service
public class SpeechService {

  private static final Logger log = LoggerFactory.getLogger(SpeechService.class);

  public record Outcome(AudioResult audio, boolean cacheHit, String cacheKey) {}

  private final EngineRegistry registry;
  private final AudioCache cache;
  private final UsageLedger ledger;
  private final AudioConverter converter;
  private final RouterProperties properties;

  public SpeechService(EngineRegistry registry, AudioCache cache, UsageLedger ledger,
      AudioConverter converter, RouterProperties properties) {
    this.registry = registry;
    this.cache = cache;
    this.ledger = ledger;
    this.converter = converter;
    this.properties = properties;
  }

  public Outcome synthesize(String apiKey, SpeechCommand command, String format) {
    String extension = format;
    List<TtsEngine> candidates = registry.candidates(command.model());
    if (candidates.isEmpty()) {
      if (registry.findEngine(command.model()) == null) {
        throw new RouterException(HttpStatus.BAD_REQUEST, "model_not_found",
            "Model '" + command.model() + "' is not served by any engine.");
      }
      throw new RouterException(HttpStatus.SERVICE_UNAVAILABLE, "engine_unavailable",
          "All engines for model '" + command.model() + "' are in cooldown.");
    }

    int textLength = command.text().length();
    // 分片按候选里最小的上限定，保证降级链路上任何引擎都接得住每一片
    int chunkLimit = candidates.stream().mapToInt(TtsEngine::maxInputChars).min().orElseThrow();
    boolean split = properties.getSplit().isEnabled() && textLength > chunkLimit;
    if (split && textLength > properties.getSplit().getMaxTotalChars()) {
      throw new RouterException(HttpStatus.BAD_REQUEST, "input_too_long",
          "input has " + textLength + " chars, split mode caps at "
              + properties.getSplit().getMaxTotalChars() + " chars.");
    }
    if (!split) {
      List<TtsEngine> usable = candidates.stream()
          .filter(engine -> engine.maxInputChars() >= textLength)
          .toList();
      if (usable.isEmpty()) {
        throw new RouterException(HttpStatus.BAD_REQUEST, "input_too_long",
            "input has " + textLength + " chars, the widest limit for model '" + command.model()
                + "' is " + registry.maxInputCharsFor(command.model())
                + " (long-text splitting is disabled).");
      }
      candidates = usable;
    }

    String cacheKey = cache.key(command.model(), command.voice(), command.speed(), format, command.text());
    byte[] cached = cache.get(cacheKey, extension);
    if (cached != null) {
      ledger.record(apiKey, textLength, true);
      log.info("cache hit key={} model={} format={}", cacheKey, command.model(), format);
      return new Outcome(new AudioResult(cached, contentType(format), "cache"), true, cacheKey);
    }

    List<String> chunks = split ? TextSplitter.split(command.text(), chunkLimit) : List.of(command.text());
    List<byte[]> wavParts = new ArrayList<>();
    Set<String> engineIds = new LinkedHashSet<>();
    for (String chunk : chunks) {
      AudioResult raw = synthesizeViaFallback(candidates,
          new SpeechCommand(command.model(), command.voice(), chunk, command.speed()));
      wavParts.add(raw.data());
      engineIds.add(raw.engineId());
    }
    byte[] wav = WavMerger.merge(wavParts);
    byte[] data = "mp3".equals(format) ? converter.toMp3(wav) : wav;
    cache.put(cacheKey, extension, data);
    ledger.record(apiKey, textLength, false);
    log.info("synthesized engines={} model={} chars={} chunks={} wav_bytes={} out_bytes={}",
        String.join("+", engineIds), command.model(), textLength, chunks.size(), wav.length, data.length);
    return new Outcome(new AudioResult(data, contentType(format), String.join("+", engineIds)),
        false, cacheKey);
  }

  /** 单片（或整段文本）按优先级逐引擎尝试，全部失败抛 503。 */
  private AudioResult synthesizeViaFallback(List<TtsEngine> candidates, SpeechCommand piece) {
    EngineException last = null;
    for (TtsEngine engine : candidates) {
      try {
        AudioResult raw = engine.synthesize(piece);
        registry.recordSuccess(engine.id());
        return raw;
      } catch (EngineException e) {
        last = e;
        registry.recordFailure(engine.id(), e.isTransientFailure());
        log.warn("engine={} failed, trying next candidate: {}", engine.id(), e.getMessage());
      }
    }
    throw new RouterException(HttpStatus.SERVICE_UNAVAILABLE, "engine_error",
        "Every engine failed for a chunk of " + piece.text().length() + " chars. Last error: "
            + (last == null ? "unknown" : last.getMessage()));
  }

  private static String contentType(String format) {
    return "mp3".equals(format) ? "audio/mpeg" : "audio/wav";
  }
}
