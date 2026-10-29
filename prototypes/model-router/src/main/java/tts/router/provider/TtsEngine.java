package tts.router.provider;

import java.util.Set;

public interface TtsEngine {

  String id();

  Set<String> models();

  int maxInputChars();

  boolean supports(String model);

  AudioResult synthesize(SpeechCommand command);
}
