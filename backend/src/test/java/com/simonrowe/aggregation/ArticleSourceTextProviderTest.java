package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Direct coverage of the source-text cascade extracted out of
 * {@code ArticleSectionWriter}. {@code ArticleSectionWriterTest} continues to exercise the
 * same cascade end to end through a real provider; these tests pin the cascade itself so a
 * future consumer can rely on it without reading the digest writer.
 */
@ExtendWith(MockitoExtension.class)
class ArticleSourceTextProviderTest {

  private static final String URL = "https://example.com/article";

  // Comfortably over MIN_USABLE_SOURCE_CHARS (500).
  private static final String LONG_SCRAPE = "Freshly scraped body text. ".repeat(25);
  private static final String LONG_STORED = "Stored full content. ".repeat(35);

  @Mock private SitemapHtmlScraper scraper;

  private ArticleSourceTextProvider provider;

  @BeforeEach
  void setUp() {
    provider = new ArticleSourceTextProvider(scraper);
  }

  @Test
  void freshScrapeOverTheUsableFloorWins() {
    stubScrape(LONG_SCRAPE);

    String text = provider.sourceTextFor(article(LONG_STORED, "Stored summary."));

    assertThat(text).isEqualTo(LONG_SCRAPE);
  }

  @Test
  void nullScrapeFallsThroughToStoredFullContent() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);

    String text = provider.sourceTextFor(article(LONG_STORED, "Stored summary."));

    assertThat(text).isEqualTo(LONG_STORED);
  }

  @Test
  void scrapeUnderTheUsableFloorFallsThroughToStoredFullContent() {
    // The classic consent-wall interstitial: short, and not the article body.
    stubScrape("Subscribe to continue reading.");

    String text = provider.sourceTextFor(article(LONG_STORED, "Stored summary."));

    assertThat(text)
        .isEqualTo(LONG_STORED)
        .doesNotContain("Subscribe to continue reading.");
  }

  @Test
  void whenScrapeAndStoredContentAreBothUnderTheFloorTheLongestOfThreeWins() {
    stubScrape("Short scrape.");
    String longestSummary = "A stored summary that happens to be the longest of the three.";

    String text = provider.sourceTextFor(article("Short stored.", longestSummary));

    assertThat(text).isEqualTo(longestSummary);
  }

  @Test
  void whenTheScrapeIsTheLongestUnderTheFloorItStillWins() {
    stubScrape("A short scrape that is nevertheless the longest candidate here.");

    String text = provider.sourceTextFor(article("Stored.", "Summary."));

    assertThat(text)
        .isEqualTo("A short scrape that is nevertheless the longest candidate here.");
  }

  @Test
  void everythingNullOrEmptyYieldsEmptyStringRatherThanNull() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);

    String text = provider.sourceTextFor(article(null, null));

    assertThat(text).isEmpty();
  }

  @Test
  void overLongTextIsTruncatedToTwelveThousandCharacters() {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(null);

    String text = provider.sourceTextFor(article("x".repeat(20_000), "Summary."));

    assertThat(text).hasSize(12_000);
  }

  @Test
  void hardFloorRejectsTextUnderTwoHundredCharactersAndAcceptsTextAtIt() {
    assertThat(ArticleSourceTextProvider.clearsHardFloor("x".repeat(199))).isFalse();
    assertThat(ArticleSourceTextProvider.clearsHardFloor("x".repeat(200))).isTrue();
    assertThat(ArticleSourceTextProvider.clearsHardFloor("")).isFalse();
    assertThat(ArticleSourceTextProvider.clearsHardFloor(null)).isFalse();
  }

  private void stubScrape(final String content) {
    when(scraper.scrapeArticlePagePublic(anyString())).thenReturn(new ScrapedContent(
        "Title", URL, content, Instant.now(), "Author", null, false));
  }

  private static AggregatedArticle article(
      final String fullContent, final String summary) {
    return new AggregatedArticle(
        "art-1", "Title", "Source", "https://example.com", URL,
        summary, fullContent, "Author", Instant.now(), Instant.now(), true, null);
  }
}
