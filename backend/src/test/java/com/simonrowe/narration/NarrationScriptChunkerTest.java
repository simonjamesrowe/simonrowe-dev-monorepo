package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationScriptChunkerTest {

  private final NarrationScriptChunker chunker = new NarrationScriptChunker();

  @Test
  void scriptThatAlreadyFitsIsNotSplit() {
    assertThat(chunker.chunk("Short enough.", 100))
        .containsExactly("Short enough.");
  }

  @Test
  void blankScriptsYieldNoChunks() {
    assertThat(chunker.chunk("", 100)).isEmpty();
    assertThat(chunker.chunk("   ", 100)).isEmpty();
    assertThat(chunker.chunk(null, 100)).isEmpty();
  }

  @Test
  void everyChunkStaysWithinTheByteBudget() {
    String script = ("The quick brown fox jumps over the lazy dog. "
        + "Pack my box with five dozen liquor jugs. ").repeat(20);

    List<String> chunks = chunker.chunk(script, 200);

    assertThat(chunks).isNotEmpty();
    assertThat(chunks).allSatisfy(c ->
        assertThat(NarrationScriptChunker.utf8Length(c)).isLessThanOrEqualTo(200));
  }

  @Test
  void splittingLosesNoWords() {
    String script = ("Alpha beta gamma delta. Epsilon zeta eta theta. ").repeat(10);

    List<String> chunks = chunker.chunk(script, 120);

    String rejoined = String.join(" ", chunks).replaceAll("\\s+", " ").trim();
    assertThat(rejoined).isEqualTo(script.replaceAll("\\s+", " ").trim());
  }

  /**
   * The boundary is audible: a cut mid-sentence puts an unnatural stop into the speech,
   * whereas a sentence end already carries one.
   */
  @Test
  void prefersSentenceBoundaries() {
    String script = "First sentence here. Second sentence here. Third sentence here.";

    List<String> chunks = chunker.chunk(script, 45);

    // Greedy: it packs as many whole sentences as fit, then breaks at a sentence end
    // rather than mid-sentence. Every chunk but a hard-split one ends in terminal
    // punctuation.
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(c -> assertThat(c).endsWith("."));
    assertThat(chunks.get(0)).isEqualTo("First sentence here. Second sentence here.");
  }

  @Test
  void doesNotBreakMidSentenceWhenBoundaryIsAvailable() {
    String script = ("A short sentence. ").repeat(20);

    List<String> chunks = chunker.chunk(script, 60);

    assertThat(chunks).allSatisfy(c -> assertThat(c).endsWith("."));
  }

  @Test
  void fallsBackToWordBoundariesWhenNoSentenceEndFits() {
    String script = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda";

    List<String> chunks = chunker.chunk(script, 20);

    assertThat(chunks).allSatisfy(c -> assertThat(c).doesNotStartWith(" "));
    // No chunk should end mid-word.
    assertThat(chunks).allSatisfy(c ->
        assertThat(script).contains(c));
  }

  /** A single token longer than the budget must still terminate rather than loop. */
  @Test
  void hardSplitsSingleOverlongToken() {
    String script = "x".repeat(500);

    List<String> chunks = chunker.chunk(script, 100);

    assertThat(chunks).hasSize(5);
    assertThat(chunks).allSatisfy(c ->
        assertThat(NarrationScriptChunker.utf8Length(c)).isLessThanOrEqualTo(100));
    assertThat(String.join("", chunks)).isEqualTo(script);
  }

  /**
   * The limit is a byte limit. Curly quotes and em dashes are three UTF-8 bytes each, so a
   * character-based split would sail straight past the ceiling.
   */
  @Test
  void countsMultiByteCharactersByTheirEncodedWidth() {
    String script = "—".repeat(100);

    List<String> chunks = chunker.chunk(script, 90);

    assertThat(chunks).allSatisfy(c ->
        assertThat(NarrationScriptChunker.utf8Length(c)).isLessThanOrEqualTo(90));
    assertThat(String.join("", chunks)).isEqualTo(script);
  }

  @Test
  void neverSplitsSurrogatePair() {
    // Each emoji is a surrogate pair and four UTF-8 bytes.
    String script = "😀".repeat(50);

    List<String> chunks = chunker.chunk(script, 30);

    assertThat(chunks).allSatisfy(c -> {
      assertThat(NarrationScriptChunker.utf8Length(c)).isLessThanOrEqualTo(30);
      // A split surrogate pair would leave an unpaired char, which encodes as '?'.
      assertThat(c.codePoints().allMatch(cp -> cp == "😀".codePointAt(0))).isTrue();
    });
    assertThat(String.join("", chunks)).isEqualTo(script);
  }

  @Test
  void rejectsNonPositiveBudget() {
    assertThatThrownBy(() -> chunker.chunk("Something to say.", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void realisticSummaryLengthSplitsIntoFewChunks() {
    // Roughly the size of a generated article summary.
    String script = ("This paragraph stands in for generated summary prose. ").repeat(85);

    List<String> chunks = chunker.chunk(script, 5000);

    assertThat(NarrationScriptChunker.utf8Length(script)).isGreaterThan(4000);
    assertThat(chunks).hasSizeLessThanOrEqualTo(2);
  }
}
