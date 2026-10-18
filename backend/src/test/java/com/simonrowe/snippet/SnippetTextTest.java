package com.simonrowe.snippet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The quote check compares what the browser rendered with what the server renders from the
 * same markdown. These cases are the shapes real summaries take.
 */
class SnippetTextTest {

  private static final String SUMMARY = """
      William Martin contends that **software development** is approaching a second major \
      shift: after programmers accepted _AI assistance_ for writing code, they may delegate \
      review as well.

      He emphasises guardrails designed around recurring AI failure modes:

      - duplication and architectural drift,
      - weak or "fabricated" tests, and
      - unsafe dependencies.

      Meta’s Forum was measured by verified, production-ready outcomes—changes that build \
      successfully—rather than by the `quantity` of code generated.
      """;

  @Test
  void rendersTheWordsWithoutTheMarkup() {
    assertThat(SnippetText.renderedText(SUMMARY))
        .startsWith("William Martin contends that software development is approaching")
        .contains("accepted AI assistance for writing code")
        .contains("by the quantity of code generated.")
        .doesNotContain("**", "_AI", "`", "- duplication");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      across emphasis      | major shift: after programmers accepted AI assistance
      across a paragraph   | delegate review as well.\\n\\nHe emphasises guardrails
      across list items    | architectural drift,\\n\\nweak or "fabricated" tests, and
      curly quotes, dashes | Meta’s Forum was measured by verified, production-ready outcomes—
      inline code          | rather than by the quantity of code generated.
      outer whitespace     | \\u00A0 second major shift \\n
      """)
  void findsQuotesTheBrowserWouldSend(final String ignored, final String exact) {
    assertThat(SnippetText.summaryContains(SUMMARY, unescape(exact))).isTrue();
  }

  @Test
  void refusesQuoteTheSummaryDoesNotContain() {
    assertThat(SnippetText.summaryContains(SUMMARY, "review models should differ")).isFalse();
    assertThat(SnippetText.summaryContains(SUMMARY, "   ")).isFalse();
    assertThat(SnippetText.summaryContains(null, "anything")).isFalse();
  }

  /** Two blocks never run together, or a quote across them would match nothing. */
  @Test
  void keepsBlocksApart() {
    assertThat(SnippetText.renderedText("# Heading\n\nFirst.\n\n> Quoted.\n\nLast."))
        .isEqualTo("Heading First. Quoted. Last.");
  }

  @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
  @CsvSource(delimiter = '|', textBlock = """
      Platform Eng Talk     | platform-eng-talk
      '  #Code-Review '     | code-review
      '##metrics'           | metrics
      '#'                   | ''
      ''                    | ''
      """)
  void normalisesTags(final String raw, final String expected) {
    assertThat(SnippetText.normaliseTag(raw)).isEqualTo(expected);
  }

  @Test
  void dropsBlankAndDuplicateTagsKeepingTheFirstOrder() {
    assertThat(SnippetText.normaliseTags(List.of("Talk", " ", "#talk", "metrics")))
        .containsExactly("talk", "metrics");
    assertThat(SnippetText.normaliseTags(null)).isEmpty();
  }

  @Test
  void refusesTooManyOrTooLongTags() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i <= SnippetText.MAX_TAGS; i++) {
      many.add("tag-" + i);
    }
    assertThatThrownBy(() -> SnippetText.normaliseTags(many))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SnippetText.normaliseTags(
        List.of("x".repeat(SnippetText.MAX_TAG_LENGTH + 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The whitespace patterns run over every quote and summary. A string built to make a
   * backtracking engine work hardest, whitespace runs broken by a non-matching character,
   * has to finish quickly and without a {@code StackOverflowError}.
   */
  @Test
  @Timeout(5)
  void whitespaceHandlingStaysLinearOnHugeInput() {
    String hostile = String.join("x", Collections.nCopies(25_000, " \t \n")) + "!";
    long started = System.nanoTime();

    String normalised = SnippetText.normalise(hostile);
    String tag = SnippetText.normaliseTag("#".repeat(100_000) + hostile);

    assertThat(normalised).hasSizeLessThan(hostile.length());
    assertThat(tag).isNotEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  private static String unescape(final String value) {
    return value.replace("\\n", "\n").replace("\\u00A0", " ");
  }
}
