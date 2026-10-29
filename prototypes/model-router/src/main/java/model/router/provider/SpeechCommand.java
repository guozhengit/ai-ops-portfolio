package model.router.provider;

public record SpeechCommand(String model, String voice, String text, float speed) {}
