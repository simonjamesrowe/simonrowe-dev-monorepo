package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.scrapers.AiHeroScraper;
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
class V064SeedAndBackfillAiHeroTest {

  @Mock private ContentSourceRepository sourceRepository;
  @Mock private ContentAggregationAgent aggregationAgent;

  private final V064SeedAndBackfillAiHero changeUnit = new V064SeedAndBackfillAiHero();

  private static ContentSource aiHero() {
    return new ContentSource(
        "aihero-1", "AI Hero", "https://www.aihero.dev", "https://www.aihero.dev/rss.xml",
        null, ContentSource.SourceType.BLOG, ContentSource.ScrapeStrategy.AI_HERO,
        true, null, null, null);
  }

  @Test
  void seedsTheAiHeroStrategyAgainstTheSiteFeed() {
    when(sourceRepository.findByName("AI Hero")).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(aiHero());

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<ContentSource> captor = ArgumentCaptor.forClass(ContentSource.class);
    verify(sourceRepository).save(captor.capture());
    ContentSource seeded = captor.getValue();
    assertThat(seeded.name()).isEqualTo("AI Hero");
    assertThat(seeded.baseUrl()).isEqualTo("https://www.aihero.dev");
    // AI_HERO reads feedUrl; with the URL in any other field the scrape finds nothing.
    assertThat(seeded.feedUrl()).isEqualTo("https://www.aihero.dev/rss.xml");
    assertThat(seeded.sitemapUrl()).isNull();
    assertThat(seeded.sourceType()).isEqualTo(ContentSource.SourceType.BLOG);
    assertThat(seeded.scrapeStrategy()).isEqualTo(ContentSource.ScrapeStrategy.AI_HERO);
    assertThat(seeded.categoryFilter()).isNull();
    assertThat(seeded.active()).isTrue();
  }

  @Test
  void backfillsFromTheScrapersOwnFloor() {
    when(sourceRepository.findByName("AI Hero")).thenReturn(Optional.empty());
    ContentSource saved = aiHero();
    when(sourceRepository.save(any())).thenReturn(saved);

    changeUnit.execution(sourceRepository, aggregationAgent);

    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    verify(aggregationAgent).backfillSource(eq(saved), since.capture());
    assertThat(since.getValue())
        .isEqualTo(AiHeroScraper.PUBLISHED_FLOOR)
        .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void doesNotReseedWhenSourceAlreadyExists() {
    when(sourceRepository.findByName("AI Hero")).thenReturn(Optional.of(aiHero()));

    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(sourceRepository, never()).save(any());
    verify(aggregationAgent, never()).backfillSource(any(), any());
  }

  @Test
  void doesNotThrowWhenBackfillFails() {
    when(sourceRepository.findByName("AI Hero")).thenReturn(Optional.empty());
    when(sourceRepository.save(any())).thenReturn(aiHero());
    doThrow(new RuntimeException("aihero.dev unreachable"))
        .when(aggregationAgent).backfillSource(any(), any());

    // Must not propagate: a failed backfill must never break app boot.
    changeUnit.execution(sourceRepository, aggregationAgent);

    verify(aggregationAgent).backfillSource(any(), any());
  }

  @Test
  void rollbackDeletesTheSource() {
    ContentSource existing = aiHero();
    when(sourceRepository.findByName("AI Hero")).thenReturn(Optional.of(existing));

    changeUnit.rollback(sourceRepository);

    verify(sourceRepository).delete(existing);
  }
}
