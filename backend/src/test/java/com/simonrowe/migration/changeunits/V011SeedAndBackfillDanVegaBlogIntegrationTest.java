package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.agents.scrapers.ScraperFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots a real Spring context with Mongock enabled (overriding the suite-wide
 * {@code mongock.enabled=false}) to prove that the {@code V011} change unit — which
 * injects the Embabel {@code @Agent} {@link com.simonrowe.agents.ContentAggregationAgent}
 * into a Mongock {@code @Execution} — wires up and runs at application boot without
 * failing startup, which is V011's central requirement.
 *
 * <p>The network is never touched: {@link ScraperFactory} is replaced with a mock
 * that returns an empty list, so the guarded backfill processes nothing and no
 * articles are written. This class uses its own context (the distinct
 * {@code @TestPropertySource} makes it a separate cache key), so enabling Mongock
 * here does not affect the other integration tests. Seeded {@code content_sources}
 * and any aggregated documents are removed in teardown so nothing leaks into the
 * shared Testcontainers MongoDB used by the rest of the suite.
 */
@TestPropertySource(properties = "mongock.enabled=true")
class V011SeedAndBackfillDanVegaBlogIntegrationTest extends AbstractIntegrationTest {

  @MockitoBean
  private ScraperFactory scraperFactory;

  @Autowired
  private ContentSourceRepository contentSourceRepository;

  @Autowired
  private AggregatedArticleRepository aggregatedArticleRepository;

  @Autowired
  private AggregatedEventRepository aggregatedEventRepository;

  @BeforeEach
  void stubScraper() {
    // Mockito already returns an empty list for unstubbed collection-returning
    // methods (which is what applied during the boot-time Mongock run); this makes
    // the network-free contract explicit for any post-boot interaction.
    when(scraperFactory.scrape(any())).thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    contentSourceRepository.deleteAll();
    aggregatedArticleRepository.deleteAll();
    aggregatedEventRepository.deleteAll();
  }

  @Test
  void seedsDanVegaSourceAtBootWithoutWritingArticles() {
    Optional<ContentSource> danVega = contentSourceRepository.findByName("Dan Vega");

    assertThat(danVega).isPresent();
    assertThat(danVega.get().scrapeStrategy())
        .isEqualTo(ContentSource.ScrapeStrategy.HTML_LISTING);
    assertThat(aggregatedArticleRepository.count()).isZero();
  }
}
