package tts.router.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 长文本切分：优先在句末标点断开，区间内无边界时才硬切。
 * 分片大小按候选引擎里最小的 max-input-chars 定，保证降级链路上任何引擎都接得住每一片。
 */
final class TextSplitter {

  /** 强边界：句末（含英文句点与换行）；窗口内找不到强边界时退而求其次用弱边界（逗号/顿号/冒号）。 */
  private static final String STRONG_ENDINGS = "。！？!?；;\n…. ";
  private static final String WEAK_ENDINGS = "，,、：:；;";

  private TextSplitter() {}

  static List<String> split(String text, int maxChars) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(start + maxChars, text.length());
      if (end < text.length()) {
        int boundary = lastEndingBefore(text, start, end, STRONG_ENDINGS);
        if (boundary < 0) {
          boundary = lastEndingBefore(text, start, end, WEAK_ENDINGS);
        }
        if (boundary > start) {
          end = boundary;
        }
      }
      parts.add(text.substring(start, end));
      start = end;
    }
    return parts;
  }

  /** 在 [start, end) 里找最后一个给定标点的后一位；找不到返回 -1。 */
  private static int lastEndingBefore(String text, int start, int end, String endings) {
    for (int i = end - 1; i > start; i--) {
      if (endings.indexOf(text.charAt(i)) >= 0) {
        return i + 1;
      }
    }
    return -1;
  }
}
