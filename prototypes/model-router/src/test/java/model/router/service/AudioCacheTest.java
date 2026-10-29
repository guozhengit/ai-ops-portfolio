package model.router.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import model.router.config.RouterProperties;

class AudioCacheTest {

  @TempDir
  Path temp;

  private AudioCache cacheWith(long maxBytes) {
    RouterProperties properties = new RouterProperties();
    properties.getCache().setDirectory(temp.resolve("cache").toString());
    properties.getCache().setMaxBytes(maxBytes);
    return new AudioCache(properties);
  }

  @Test
  void roundTrip() {
    AudioCache cache = cacheWith(1024);
    cache.put("k1", "wav", new byte[] {1, 2, 3});
    assertThat(cache.get("k1", "wav")).containsExactly(1, 2, 3);
    assertThat(cache.get("missing", "wav")).isNull();
  }

  @Test
  void evictsLeastRecentlyUsedWhenOverBudget() throws InterruptedException {
    // Windows 文件时间戳有效粒度可达 ~15ms，步进之间必须留间隔，否则 mtime 平局会让淘汰目标不确定
    AudioCache cache = cacheWith(6);
    cache.put("a", "wav", new byte[3]);
    Thread.sleep(30);
    cache.put("b", "wav", new byte[3]);
    Thread.sleep(30);
    cache.get("a", "wav");
    Thread.sleep(30);
    cache.put("c", "wav", new byte[3]);

    assertThat(cache.get("b", "wav")).as("b is least recently used and should be evicted").isNull();
    assertThat(cache.get("a", "wav")).isNotNull();
    assertThat(cache.get("c", "wav")).isNotNull();
  }

  @Test
  void statsReflectFilesOnDisk() {
    AudioCache cache = cacheWith(1024);
    cache.put("x", "mp3", new byte[10]);
    cache.put("y", "wav", new byte[5]);

    AudioCache.CacheStats stats = cache.stats();
    assertThat(stats.files()).isEqualTo(2);
    assertThat(stats.bytes()).isEqualTo(15);
    assertThat(stats.maxBytes()).isEqualTo(1024);
  }
}
