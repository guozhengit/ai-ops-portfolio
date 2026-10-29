package model.router.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextSplitterTest {

  @Test
  void keepsShortTextAsSingleChunk() {
    assertThat(TextSplitter.split("短文本", 100)).containsExactly("短文本");
  }

  @Test
  void prefersSentenceBoundaries() {
    // "第一句。第二句。第三句。"，上限 9：边界搜索应落在第二个句号后
    List<String> parts = TextSplitter.split("第一句。第二句。第三句。", 9);
    assertThat(parts).containsExactly("第一句。第二句。", "第三句。");
    assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(9));
  }

  @Test
  void hardSplitsWithoutBoundaries() {
    assertThat(TextSplitter.split("长".repeat(25), 10))
        .containsExactly("长".repeat(10), "长".repeat(10), "长".repeat(5));
  }

  @Test
  void prefersStrongBoundariesOverWeakOnes() {
    // 窗口 [0,7) 内句号在前、逗号在后：宁可提前断句也不用逗号凑近上限
    List<String> parts = TextSplitter.split("甲乙。丙丁，戊己。", 7);
    assertThat(parts.get(0)).isEqualTo("甲乙。");
    assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(7));
  }

  @Test
  void fallsBackToWeakBoundariesWhenNoStrong() {
    // 全是逗号没有句末标点：在逗号处断开而不是硬切
    assertThat(TextSplitter.split("第一段，第二段，第三段", 8))
        .containsExactly("第一段，第二段，", "第三段");
  }

  @Test
  void englishSentencesSplitOnPeriods() {
    List<String> parts = TextSplitter.split("Hello world. Hello again. Tail", 13);
    assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(13));
    assertThat(parts.get(0)).endsWith(". ");
  }

  @Test
  void everyChunkRespectsLimit() {
    String text = "这是第一句话！这是第二句话？这是第三句话。最后一句没有标点结束";
    for (int limit : List.of(5, 7, 12, 30)) {
      assertThat(TextSplitter.split(text, limit))
          .as("limit=" + limit)
          .allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(limit));
    }
  }
}
