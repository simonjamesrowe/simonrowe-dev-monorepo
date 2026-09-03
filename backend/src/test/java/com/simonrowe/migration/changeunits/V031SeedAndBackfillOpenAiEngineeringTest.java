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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V031SeedAndBackfillOpenAiEngineeringTest {

  private static final String SOURCE_NAME = "OpenAI Engineering";

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V031SeedAndBackfillOpenAiEngineering changeUnit =
      new V031SeedAndBackfillOpenAiEngineering();

  private static ContentSource openAiEngineering() {
    return new ContentSource(
        "openai-1", SOURCE_NAME, "https://openai.com/news/engineering/",
        "https://openai.com/news/rss.xml", null,
        ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.RSS, true, null, null, "Engineering");
  }

  @Test
  void seedsSourceAndBackfillsWhenAbsent() {
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.empty());
    ContentSource saved = openAiEngineering();
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo(SOURCE_NAME);
    assertThat(captor.getValue().baseUrl()).isEqualTo("https://openai.com/news/engineering/");
    assertThat(captor.getValue().sourceType()).isEqualTo(ContentSource.SourceType.NEWS);
    assertThat(captor.getValue().active()).isTrue();
    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void usesRssStrategyAgainstTheSiteWideFeed() {
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(openAiEngineering());

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    // Every HTML page on openai.com answers 403 to the scrapers' user agent, so the
    // XML feed is the only readable surface — neither HTML strategy can be used here.
    assertThat(captor.getValue().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.RSS);
    assertThat(captor.getValue().feedUrl()).isEqualTo("https://openai.com/news/rss.xml");
    assertThat(captor.getValue().sitemapUrl()).isNull();
  }

  @Test
  void restrictsIngestionToTheEngineeringCategory() {
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(openAiEngineering());

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    // Without this the source is OpenAI's entire output — over a thousand items across
    // Company, Product, Research, Global Affairs and twenty more categories.
    assertThat(captor.getValue().categoryFilter()).isEqualTo("Engineering");
  }

  @Test
  void backfillsFromFixedInstantRatherThanRelativeWindow() {
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(openAiEngineering());

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(aggregationAgent).backfillSource(any(), since.capture());
    // A relative window would make the backfill's size depend on when this first runs,
    // so an environment created later would silently ingest less of a fixed archive.
    assertThat(since.getValue()).isEqualTo(Instant.parse("2025-10-01T00:00:00Z"));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    when(sourceRepository.findByName(SOURCE_NAME))
        .thenReturn(Optional.of(openAiEngineering()));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.empty());
    ContentSource saved = openAiEngineering();
    when(sourceRepository.save(any())).thenReturn(saved);
    doThrow(new RuntimeException("LLM unavailable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate — a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(eq(saved), any(Instant.class));
  }

  @Test
  void rollbackDeletesTheSource() {
    ContentSource existing = openAiEngineering();
    when(sourceRepository.findByName(SOURCE_NAME)).thenReturn(Optional.of(existing));

    changeUnit.rollback(sourceRepository);

    verify(sourceRepository).delete(existing);
  }
}
