package tts.router.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public record SpeechRequest(
    @NotBlank String model,
    @NotBlank String input,
    @NotBlank String voice,
    @JsonProperty("response_format") String responseFormat,
    @DecimalMin("0.25") @DecimalMax("4.0") Float speed) {

  public float speedOrDefault() {
    return speed == null ? 1.0f : speed;
  }

  public String responseFormatOrDefault() {
    return responseFormat == null || responseFormat.isBlank() ? "wav" : responseFormat.toLowerCase();
  }
}
