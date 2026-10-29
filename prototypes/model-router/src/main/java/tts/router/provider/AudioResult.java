package tts.router.provider;

public record AudioResult(byte[] data, String contentType, String engineId) {}
