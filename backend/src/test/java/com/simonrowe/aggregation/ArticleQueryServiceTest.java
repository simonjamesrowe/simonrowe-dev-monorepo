package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

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
  void addsNoClausesWhenThereIsNothingToFilterOn() {
    assertThat(ArticleQueryService.filterClauses(null, "  ", List.of("title"))).isEmpty();
    assertThat(ArticleQueryService.filterClauses(List.of(" ", ""), null, List.of("title")))
        .isEmpty();
    assertThat(ArticleQueryService.combine(List.of()).getCriteriaObject()).isEmpty();
  }

  @Test
  void addsOneSourceClauseAndOneClausePerTerm() {
    List<Criteria> clauses = ArticleQueryService.filterClauses(
        List.of("Spring Blog"), "kafka tuning", List.of("title", "summary"));

    assertThat(clauses).hasSize(3);
    assertThat(clauses.get(0).getCriteriaObject().toJson()).contains("Spring Blog");
  }

  @Test
  void quotesEachTermSoRegexMetacharactersMatchLiterally() {
    List<Criteria> clauses = ArticleQueryService.filterClauses(
        null, "C++", List.of("title"));

    assertThat(clauses.get(0).getCriteriaObject().toJson()).contains("\\\\QC++\\\\E");
  }

  @Test
  void truncatesBeforeSplitting() {
    String overlong = "a".repeat(ArticleQueryService.MAX_QUERY_LENGTH) + " trailing";

    assertThat(ArticleQueryService.terms(overlong)).containsExactly(
        "a".repeat(ArticleQueryService.MAX_QUERY_LENGTH));
  }
}
