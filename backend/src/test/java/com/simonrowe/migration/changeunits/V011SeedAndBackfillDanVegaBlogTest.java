package com.simonrowe.migration.changeunits;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V011SeedAndBackfillDanVegaBlogTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V011SeedAndBackfillDanVegaBlog changeUnit =
      new V011SeedAndBackfillDanVegaBlog();

  @Test
  void seedsSourceAndBackfillsWhenAbsent() {
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.empty());
    ContentSource saved = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor =
        ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().name())
        .isEqualTo("Dan Vega");
    org.assertj.core.api.Assertions.assertThat(
        captor.getValue().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.HTML_LISTING);
    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    ContentSource existing = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.of(existing));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName("Dan Vega"))
        .thenReturn(Optional.empty());
    ContentSource saved = new ContentSource(
        "dv1", "Dan Vega", "https://www.danvega.dev/blog", null, null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.HTML_LISTING, true, null, null);
    when(sourceRepository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("LLM unavailable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate — a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }
}
