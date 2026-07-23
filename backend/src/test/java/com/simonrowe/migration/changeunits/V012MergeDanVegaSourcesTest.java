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
class V012MergeDanVegaSourcesTest {

  @Mock private AggregatedArticleRepository articleRepository;

  private final V012MergeDanVegaSources changeUnit = new V012MergeDanVegaSources();

  private static AggregatedArticle article(final String id, final String sourceName) {
    return new AggregatedArticle(
        id, "Title " + id, sourceName, "https://www.danvega.dev/blog",
        "https://www.danvega.dev/blog/" + id, "summary", "content", "Dan Vega",
        Instant.now(), Instant.now(), true, null);
  }

  @Test
  void reTagsLegacyArticlesToCanonicalSource() {
    when(articleRepository.findBySourceName("danvega.dev"))
        .thenReturn(List.of(article("a", "danvega.dev"), article("b", "danvega.dev")));

    changeUnit.execution(articleRepository);

    ArgumentCaptor<AggregatedArticle> captor =
        ArgumentCaptor.forClass(AggregatedArticle.class);
    verify(articleRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(AggregatedArticle::sourceName)
        .containsOnly("Dan Vega");
    assertThat(captor.getAllValues())
        .extracting(AggregatedArticle::id)
        .containsExactly("a", "b");
  }

  @Test
  void doesNothingWhenNoLegacyArticles() {
    when(articleRepository.findBySourceName("danvega.dev"))
        .thenReturn(List.of());

    changeUnit.execution(articleRepository);

    verify(articleRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
