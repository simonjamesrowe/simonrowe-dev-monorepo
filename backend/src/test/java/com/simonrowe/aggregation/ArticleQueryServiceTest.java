package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The caps on what a free-text filter is allowed to ask of a collection scan.
 *
 * <p>Matching behaviour itself is covered by {@link NewsControllerTest} against a real
 * Mongo; what is worth pinning here is that neither cap can be argued away by a long
 * enough query, because each term costs another pass over every candidate document.
 */
class ArticleQueryServiceTest {

  @Test
  void findsNoTermsInAnAbsentOrEmptyQuery() {
    assertThat(ArticleQueryService.terms(null)).isEmpty();
    assertThat(ArticleQueryService.terms("")).isEmpty();
    assertThat(ArticleQueryService.terms("   ")).isEmpty();
  }

  @Test
  void splitsOnAnyRunOfWhitespace() {
    assertThat(ArticleQueryService.terms("  spring   boot\t4 ")).containsExactly(
        "spring", "boot", "4");
  }

  @Test
  void keepsAtMostSixTerms() {
    String tenWords = "one two three four five six seven eight nine ten";

    assertThat(ArticleQueryService.terms(tenWords))
        .hasSize(ArticleQueryService.MAX_TERMS)
        .containsExactly("one", "two", "three", "four", "five", "six");
  }

  @Test
  void truncatesBeforeSplitting() {
    String overlong = "a".repeat(ArticleQueryService.MAX_QUERY_LENGTH) + " trailing";

    assertThat(ArticleQueryService.terms(overlong)).containsExactly(
        "a".repeat(ArticleQueryService.MAX_QUERY_LENGTH));
  }
}
