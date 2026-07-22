package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V010BackfillArticlePublishedDatesTest {

  @Mock private AggregatedArticleRepository articleRepository;

  private final V010BackfillArticlePublishedDates changeUnit =
      new V010BackfillArticlePublishedDates();

  private static AggregatedArticle article(
      final String id, final Instant publishedDate, final Instant fetchedAt) {
    return new AggregatedArticle(
        id, "Title " + id, "aws.amazon.com", "https://aws.amazon.com",
        "https://aws.amazon.com/" + id, "summary", "content", "author",
        publishedDate, fetchedAt, true, null);
  }

  @Test
  void backfillsPublishedDateFromFetchedAtWhenMissing() {
    Instant fetchedAt = Instant.parse("2026-07-20T10:00:00Z");
    when(articleRepository.findAll())
        .thenReturn(List.of(article("dateless", null, fetchedAt)));

    changeUnit.execution(articleRepository);

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository).save(captor.capture());
    assertThat(captor.getValue().publishedDate()).isEqualTo(fetchedAt);
    assertThat(captor.getValue().fetchedAt()).isEqualTo(fetchedAt);
  }

  @Test
  void leavesArticlesWithAnExistingPublishedDateUntouched() {
    Instant published = Instant.parse("2025-01-01T00:00:00Z");
    when(articleRepository.findAll())
        .thenReturn(List.of(article("dated", published, Instant.now())));

    changeUnit.execution(articleRepository);

    verify(articleRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
