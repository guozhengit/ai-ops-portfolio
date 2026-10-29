package model.router.provider;

import java.util.Set;

/** dev profile 用的必然失败的引擎，用来验证降级链路真的会被触发。 */
public class BrokenEngine implements TtsEngine {

  @Override
  public String id() {
    return "dev-broken";
  }

  @Override
  public Set<String> models() {
    return Set.of("dev-kokoro");
  }

  @Override
  public int maxInputChars() {
    return 4096;
  }

  @Override
  public boolean supports(String model) {
    return models().contains(model);
  }

  @Override
  public AudioResult synthesize(SpeechCommand command) {
    throw new EngineException("dev-broken is intentionally down");
  }
}
