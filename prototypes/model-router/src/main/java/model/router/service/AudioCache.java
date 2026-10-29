package model.router.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import model.router.config.RouterProperties;

/**
 * 音频体积大，2C2G 上放不进内存，直接落盘并按总量淘汰最久未用的文件。
 * 总大小在内存里增量估算（驱逐后用全量扫描校准），避免每次写缓存都扫目录。
 */
@Component
public class AudioCache {

  private static final Logger log = LoggerFactory.getLogger(AudioCache.class);

  public record CacheStats(long files, long bytes, long maxBytes) {}

  private record Snapshot(long files, long bytes) {}

  private final RouterProperties properties;
  private final Path directory;
  private long totalBytes;

  public AudioCache(RouterProperties properties) {
    this.properties = properties;
    this.directory = Path.of(properties.getCache().getDirectory());
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create cache directory " + directory, e);
    }
    this.totalBytes = snapshot().bytes();
  }

  public String key(String model, String voice, float speed, String format, String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(
          (model + "|" + voice + "|" + speed + "|" + format + "|" + text).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public synchronized byte[] get(String key, String extension) {
    if (!properties.getCache().isEnabled()) {
      return null;
    }
    Path file = directory.resolve(key + "." + extension);
    if (!Files.exists(file)) {
      return null;
    }
    try {
      byte[] data = Files.readAllBytes(file);
      touch(file);
      return data;
    } catch (IOException e) {
      log.warn("cache read failed key={} error={}", key, e.toString());
      return null;
    }
  }

  public synchronized void put(String key, String extension, byte[] data) {
    if (!properties.getCache().isEnabled()) {
      return;
    }
    try {
      Path file = directory.resolve(key + "." + extension);
      Files.write(file, data);
      totalBytes += sizeOf(file);
      evictIfNeeded();
    } catch (IOException e) {
      log.warn("cache write failed key={} error={}", key, e.toString());
    }
  }

  public synchronized CacheStats stats() {
    Snapshot snap = snapshot();
    return new CacheStats(snap.files(), snap.bytes(), properties.getCache().getMaxBytes());
  }

  /** 命中即续命：把 mtime 刷成当前时间，淘汰才真正是「最久未用」而不是「最早写入」。 */
  private void touch(Path file) {
    try {
      Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
    } catch (IOException e) {
      log.debug("cache touch failed file={} error={}", file, e.toString());
    }
  }

  private void evictIfNeeded() throws IOException {
    long maxBytes = properties.getCache().getMaxBytes();
    if (totalBytes <= maxBytes) {
      return;
    }
    List<Path> files;
    try (var stream = Files.list(directory)) {
      // Stream.toList() 是不可变列表，后面要按 mtime 排序，必须包一层
      files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
    }
    files.sort(Comparator.comparingLong(this::lastModifiedMillis));
    for (Path file : files) {
      if (totalBytes <= maxBytes) {
        break;
      }
      long size = sizeOf(file);
      if (Files.deleteIfExists(file)) {
        totalBytes -= size;
        log.info("cache evicted file={} size={}", file.getFileName(), size);
      }
    }
    totalBytes = snapshot().bytes();
  }

  private Snapshot snapshot() {
    try (var stream = Files.list(directory)) {
      List<Path> files = stream.filter(Files::isRegularFile).toList();
      long total = 0;
      for (Path file : files) {
        total += sizeOf(file);
      }
      return new Snapshot(files.size(), total);
    } catch (IOException e) {
      log.warn("cache scan failed: {}", e.toString());
      return new Snapshot(0, 0);
    }
  }

  private long lastModifiedMillis(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }

  private long sizeOf(Path path) {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class).size();
    } catch (IOException e) {
      return 0L;
    }
  }
}
