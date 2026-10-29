package model.router.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import model.router.config.RouterProperties;

@Component
public class EngineRegistry {

  private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

  public record EngineStatus(String id, int priority, Set<String> models, boolean available,
                             Instant cooldownUntil) {}

  private final List<Binding> bindings;
  private final RouterProperties properties;
  private final Map<String, Integer> failures = new ConcurrentHashMap<>();
  private final Map<String, Instant> openUntil = new ConcurrentHashMap<>();

  public EngineRegistry(List<Binding> bindings, RouterProperties properties) {
    this.bindings = bindings.stream()
        .sorted(Comparator.comparingInt(Binding::priority))
        .toList();
    this.properties = properties;
    this.bindings.forEach(binding -> log.info("engine registered id={} priority={} models={}",
        binding.engine().id(), binding.priority(), binding.engine().models()));
  }

  public List<TtsEngine> candidates(String model) {
    Instant now = Instant.now();
    return bindings.stream()
        .filter(binding -> binding.engine().supports(model))
        .filter(binding -> isAvailable(binding.engine().id(), now))
        .map(Binding::engine)
        .toList();
  }

  public List<TtsEngine> allEngines() {
    return bindings.stream().map(Binding::engine).toList();
  }

  public TtsEngine findEngine(String model) {
    return bindings.stream()
        .map(Binding::engine)
        .filter(engine -> engine.supports(model))
        .findFirst()
        .orElse(null);
  }

  /** 支持该 model 的引擎里最宽的字符上限，供 input_too_long 报错时给出可达的上限。 */
  public int maxInputCharsFor(String model) {
    return bindings.stream()
        .map(Binding::engine)
        .filter(engine -> engine.supports(model))
        .mapToInt(TtsEngine::maxInputChars)
        .max()
        .orElse(0);
  }

  public LinkedHashMap<String, String> modelOwners() {
    LinkedHashMap<String, String> owners = new LinkedHashMap<>();
    for (TtsEngine engine : allEngines()) {
      for (String model : new TreeSet<>(engine.models())) {
        owners.putIfAbsent(model, engine.id());
      }
    }
    return owners;
  }

  /** 每个引擎的健康快照，供状态页展示；不经过缓存，每次现查。 */
  public List<EngineStatus> statuses() {
    Instant now = Instant.now();
    List<EngineStatus> result = new ArrayList<>();
    for (Binding binding : bindings) {
      String id = binding.engine().id();
      boolean available = isAvailable(id, now);
      result.add(new EngineStatus(id, binding.priority(), binding.engine().models(), available,
          openUntil.get(id)));
    }
    return result;
  }

  public void recordSuccess(String engineId) {
    failures.remove(engineId);
    openUntil.remove(engineId);
  }

  public void recordFailure(String engineId, boolean transientFailure) {
    if (!transientFailure) {
      return;
    }
    int count = failures.merge(engineId, 1, Integer::sum);
    if (count >= properties.getHealth().getFailureThreshold()) {
      Instant until = Instant.now().plus(properties.getHealth().getCooldown());
      openUntil.put(engineId, until);
      failures.remove(engineId);
      log.warn("engine={} circuit opened until={} after {} failures", engineId, until, count);
    }
  }

  private boolean isAvailable(String engineId, Instant now) {
    Instant until = openUntil.get(engineId);
    if (until == null) {
      return true;
    }
    if (until.isAfter(now)) {
      return false;
    }
    openUntil.remove(engineId, until);
    return true;
  }
}
