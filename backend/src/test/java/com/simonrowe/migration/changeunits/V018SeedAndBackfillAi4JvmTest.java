package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V018SeedAndBackfillAi4JvmTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V018SeedAndBackfillAi4Jvm changeUnit = new V018SeedAndBackfillAi4Jvm();

  private static ContentSource ai4jvm() {
    return new ContentSource(
        "ai4jvm-1", "AI4JVM", "https://ai4jvm.com", null, null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.LINK_ROUNDUP, true, null, null);
  }

  @Test
  void seedsSourceAndBackfillsWhenAbsent() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    ContentSource saved = ai4jvm();
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("AI4JVM");
    assertThat(captor.getValue().baseUrl()).isEqualTo("https://ai4jvm.com");
    assertThat(captor.getValue().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.LINK_ROUNDUP);
    assertThat(captor.getValue().sourceType()).isEqualTo(ContentSource.SourceType.NEWS);
    assertThat(captor.getValue().active()).isTrue();
    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void backfillsOverA120DayWindow() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(ai4jvm());
    Instant before = Instant.now();

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(aggregationAgent).backfillSource(any(), since.capture());
    // The curated list spans months; a 30-day window would discard most of it on the
    // one run that can ever see it, since the page only ever grows at the top.
    assertThat(since.getValue())
        .isBetween(before.minus(121, ChronoUnit.DAYS), before.minus(119, ChronoUnit.DAYS));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.of(ai4jvm()));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.empty());
    ContentSource saved = ai4jvm();
    when(sourceRepository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("LLM unavailable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate — a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void rollbackDeletesTheSource() {
    ContentSource existing = ai4jvm();
    when(sourceRepository.findByName("AI4JVM")).thenReturn(Optional.of(existing));

    changeUnit.rollback(sourceRepository);

    verify(sourceRepository).delete(existing);
  }
}
