package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RssScraperTest {

  private RssScraper rssScraper;

  @BeforeEach
  void setUp() {
    rssScraper = new RssScraper();
  }

  @Test
  void scrape_parsesAllEntriesFromValidFeed() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results).hasSize(3);
  }

  @Test
  void scrape_mapsEntryTitleAndUrl() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results.get(0).title()).isEqualTo("First Article");
    assertThat(results.get(0).url()).isEqualTo("https://example.com/articles/first");
  }

  @Test
  void scrape_stripsHtmlFromDescription() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    // HTML tags should be stripped; plain text content is returned
    assertThat(results.get(0).content()).isEqualTo("This is the first article description.");
    assertThat(results.get(1).content()).isEqualTo("Second article description with emphasis.");
  }

  @Test
  void scrape_parsesPublishedDate() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results.get(0).publishedDate()).isNotNull();
    assertThat(results.get(1).publishedDate()).isNotNull();
  }

  @Test
  void scrape_returnsNullPublishedDateWhenMissing() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    ScrapedContent noDateArticle = results.get(2);
    assertThat(noDateArticle.title()).isEqualTo("No Date Article");
    assertThat(noDateArticle.publishedDate()).isNull();
  }

  @Test
  void scrape_setsIsEventFalseByDefault() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results).allMatch(c -> !c.isEvent());
  }

  @Test
  void scrape_setsIsEventTrueWhenFlagPassed() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl, true);

    assertThat(results).allMatch(ScrapedContent::isEvent);
  }

  @Test
  void scrape_returnsEmptyListForInvalidUrl() {
    List<ScrapedContent> results = rssScraper.scrape("https://invalid.local/no-such-feed.xml");

    assertThat(results).isEmpty();
  }

  @Test
  void scrape_returnsEmptyListForMalformedUrl() {
    List<ScrapedContent> results = rssScraper.scrape("not-a-url");

    assertThat(results).isEmpty();
  }

  @Test
  void scrape_imageUrlIsNullWhenNotPresentInFeed() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results).allMatch(c -> c.imageUrl() == null);
  }

  @Test
  void scrape_venueAndLocationAreNullForRssEntries() {
    String feedUrl = testFeedUrl();

    List<ScrapedContent> results = rssScraper.scrape(feedUrl);

    assertThat(results).allMatch(c -> c.venue() == null);
    assertThat(results).allMatch(c -> c.location() == null);
  }

  // Resolve the test RSS XML file packaged under src/test/resources
  private String testFeedUrl() {
    URL resource = getClass().getClassLoader().getResource("test-feed.xml");
    assertThat(resource)
        .as("test-feed.xml must exist in src/test/resources")
        .isNotNull();
    return resource.toString();
  }
}
