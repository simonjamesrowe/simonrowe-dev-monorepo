package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/** The admin listings' sort, visibility and bulk-action parameter parsing. */
class AdminSortTest {

  @Test
  void defaultsToTheDefaultFieldDescendingWithAnIdTiebreaker() {
    Sort sort = AdminSort.of(null, null,
        ArticleQueryService.ADMIN_SORT_FIELDS, ArticleQueryService.ADMIN_DEFAULT_SORT);

    assertThat(sort.toList()).containsExactly(
        Sort.Order.desc("publishedDate"), Sort.Order.asc("id"));
  }

  @Test
  void acceptsAnAllowedFieldInEitherDirectionAndAnyCase() {
    Sort sort = AdminSort.of("title", "ASC",
        ArticleQueryService.ADMIN_SORT_FIELDS, ArticleQueryService.ADMIN_DEFAULT_SORT);

    assertThat(sort.toList()).containsExactly(Sort.Order.asc("title"), Sort.Order.asc("id"));
  }

  @Test
  void refusesFieldsOffTheAllowlistRatherThanFallingBack() {
    assertThatThrownBy(() -> AdminSort.of("fullContent", "desc",
        ArticleQueryService.ADMIN_SORT_FIELDS, ArticleQueryService.ADMIN_DEFAULT_SORT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sort must be one of");
  }

  @Test
  void eventsDoNotAcceptArticleOnlyFields() {
    assertThatThrownBy(() -> AdminSort.of("fetchedAt", "desc",
        EventQueryService.ADMIN_SORT_FIELDS, EventQueryService.ADMIN_DEFAULT_SORT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAnUnknownDirection() {
    assertThatThrownBy(() -> AdminSort.of("title", "up",
        ArticleQueryService.ADMIN_SORT_FIELDS, ArticleQueryService.ADMIN_DEFAULT_SORT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("direction must be asc or desc");
  }

  @Test
  void visibilityDefaultsToAllAndRefusesUnknownValues() {
    assertThat(VisibilityFilter.parse(null)).isEqualTo(VisibilityFilter.ALL);
    assertThat(VisibilityFilter.parse(" ")).isEqualTo(VisibilityFilter.ALL);
    assertThat(VisibilityFilter.parse("Hidden")).isEqualTo(VisibilityFilter.HIDDEN);
    assertThat(VisibilityFilter.ALL.criteria()).isEmpty();
    assertThatThrownBy(() -> VisibilityFilter.parse("some"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bulkActionParsesKnownActionsOnly() {
    assertThat(List.of("hide", "SHOW", " delete ").stream().map(BulkAction::parse))
        .containsExactly(BulkAction.HIDE, BulkAction.SHOW, BulkAction.DELETE);
    assertThatThrownBy(() -> BulkAction.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BulkAction.parse("archive"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
