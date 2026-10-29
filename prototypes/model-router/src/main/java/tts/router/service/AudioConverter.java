package tts.router.service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tts.router.config.RouterProperties;
import tts.router.web.RouterException;

/**
 * 引擎侧只负责出 WAV，格式转换统一在路由器做，这样 mp3 不需要每个引擎各实现一遍。
 * 用 stdin/stdout 管道，避免临时文件带来的清理与并发命名问题。
 * 约束：ffmpeg 的三根管道都可能独立卡死，stdin/stdout/stderr 各放一个虚拟线程 Future
 * 共享同一个 deadline，任何一段超时就 destroyForcibly 断开管道，请求线程绝不无限等待。
 */
@Service
public class AudioConverter {

  private static final Logger log = LoggerFactory.getLogger(AudioConverter.class);

  private final RouterProperties properties;

  public AudioConverter(RouterProperties properties) {
    this.properties = properties;
  }

  public byte[] toMp3(byte[] wav) {
    String ffmpegPath = properties.getAudio().getFfmpegPath();
    if (!Files.isExecutable(Path.of(ffmpegPath))) {
      throw new RouterException(HttpStatus.SERVICE_UNAVAILABLE, "converter_unavailable",
          "ffmpeg not found at " + ffmpegPath);
    }

    long deadline = System.nanoTime() + properties.getAudio().getTimeout().toNanos();
    Process process = null;
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      final Process proc = new ProcessBuilder(
          ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
          "-f", "wav", "-i", "pipe:0",
          "-f", "mp3", "-codec:a", "libmp3lame", "-b:a", properties.getAudio().getMp3Bitrate(),
          "pipe:1")
          .start();
      process = proc;

      Future<?> writeIn = pool.submit(() -> {
        try (OutputStream stdin = proc.getOutputStream()) {
          stdin.write(wav);
          stdin.flush();
        } catch (Exception e) {
          log.debug("ffmpeg stdin closed early: {}", e.toString());
        }
      });
      Future<byte[]> readErr = pool.submit(() -> readAll(proc.getErrorStream()));
      Future<byte[]> readOut = pool.submit(() -> readAll(proc.getInputStream()));

      byte[] mp3 = await(readOut, deadline);
      await(writeIn, deadline);
      String err = new String(await(readErr, deadline), StandardCharsets.UTF_8);

      if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
        throw new RouterException(HttpStatus.GATEWAY_TIMEOUT, "conversion_timeout",
            "ffmpeg timed out after " + properties.getAudio().getTimeout().toSeconds() + "s");
      }
      int exit = process.exitValue();
      if (exit != 0 || mp3.length == 0) {
        throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "conversion_failed",
            "ffmpeg exit=" + exit + " stderr=" + err.trim());
      }
      return mp3;
    } catch (RouterException e) {
      if (process != null) {
        process.destroyForcibly();
      }
      throw e;
    } catch (Exception e) {
      if (process != null) {
        process.destroyForcibly();
      }
      throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "conversion_error",
          "ffmpeg invocation failed: " + e.getMessage());
    } finally {
      pool.shutdownNow();
      if (process != null) {
        process.destroy();
      }
    }
  }

  private static <T> T await(Future<T> future, long deadline) {
    try {
      return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw new RouterException(HttpStatus.GATEWAY_TIMEOUT, "conversion_timeout",
          "ffmpeg pipe stage timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "conversion_error",
          "conversion interrupted");
    } catch (ExecutionException e) {
      throw new RouterException(HttpStatus.INTERNAL_SERVER_ERROR, "conversion_error",
          "ffmpeg pipe failed: " + e.getCause());
    }
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private static byte[] readAll(InputStream in) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    in.transferTo(out);
    return out.toByteArray();
  }
}
