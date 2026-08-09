package com.simonrowe.agents.scrapers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSource.ScrapeStrategy;
import com.simonrowe.aggregation.ContentSource.SourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScraperFactoryTest {

  @Mock
  private RssScraper rssScraper;

  @Mock
  private SitemapHtmlScraper sitemapHtmlScraper;

  @Mock
  private LumaApiScraper lumaApiScraper;

  @Mock
  private LinkRoundupScraper linkRoundupScraper;

  @InjectMocks
  private ScraperFactory scraperFactory;

  @Test
  void scrape_delegatesToRssScraperForRssStrategy() {
    ContentSource source = contentSource(
        "https://example.com/feed.xml", null, null, SourceType.NEWS, ScrapeStrategy.RSS);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Title", "https://example.com/a", "Body",
            Instant.now(), "Author", null, false));
    when(rssScraper.scrape("https://example.com/feed.xml", false)).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(rssScraper).scrape("https://example.com/feed.xml", false);
    verifyNoInteractions(sitemapHtmlScraper, lumaApiScraper, linkRoundupScraper);
  }

  @Test
  void scrape_delegatesToRssScraperWithIsEventTrueForEventsSourceType() {
    ContentSource source = contentSource(
        "https://example.com/events.xml", null, null, SourceType.EVENTS, ScrapeStrategy.RSS);
    when(rssScraper.scrape("https://example.com/events.xml", true)).thenReturn(List.of());

    scraperFactory.scrape(source);

    verify(rssScraper).scrape("https://example.com/events.xml", true);
    verifyNoInteractions(sitemapHtmlScraper, lumaApiScraper, linkRoundupScraper);
  }

  @Test
  void scrape_delegatesToSitemapHtmlScraperForSitemapStrategy() {
    ContentSource source = contentSource(
        null, null, "https://example.com/sitemap.xml", SourceType.NEWS, ScrapeStrategy.SITEMAP_HTML);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Article", "https://example.com/blog/1", "Content",
            Instant.now(), null, null, false));
    when(sitemapHtmlScraper.scrape("https://example.com/sitemap.xml")).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(sitemapHtmlScraper).scrape("https://example.com/sitemap.xml");
    verifyNoInteractions(rssScraper, lumaApiScraper, linkRoundupScraper);
  }

  @Test
  void scrape_delegatesToSitemapHtmlScraperEventsPageForHtmlStrategy() {
    ContentSource source = contentSource(
        null, "https://example.com/events", null, SourceType.EVENTS, ScrapeStrategy.HTML);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Event One", "https://example.com/events/1", "Description",
            Instant.now(), null, null, true));
    when(sitemapHtmlScraper.scrapeEventsPage("https://example.com/events")).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(sitemapHtmlScraper).scrapeEventsPage("https://example.com/events");
    verifyNoInteractions(rssScraper, lumaApiScraper, linkRoundupScraper);
  }

  @Test
  void scrape_delegatesToLumaScraperForLumaStrategy() {
    ContentSource source = contentSource(
        "cal-abc123", null, null, SourceType.EVENTS, ScrapeStrategy.LUMA);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Luma Event", "https://lu.ma/event-abc", "Details",
            Instant.now(), null, "https://example.com/cover.jpg", true, "Tech Hub", "London, UK"));
    when(lumaApiScraper.scrape("cal-abc123")).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(lumaApiScraper).scrape("cal-abc123");
    verifyNoInteractions(rssScraper, sitemapHtmlScraper, linkRoundupScraper);
  }

  @Test
  void scrape_delegatesToLinkRoundupScraperForLinkRoundupStrategy() {
    ContentSource source = contentSource(
        null, "https://ai4jvm.com", null, SourceType.NEWS, ScrapeStrategy.LINK_ROUNDUP);
    List<ScrapedContent> expected = List.of(
        new ScrapedContent("Embabel 1.0.0 Reaches GA",
            "https://github.com/embabel/embabel-agent/releases/tag/v1.0.0",
            "First stable release", null, null, null, false));
    when(linkRoundupScraper.scrape("https://ai4jvm.com")).thenReturn(expected);

    List<ScrapedContent> result = scraperFactory.scrape(source);

    assertThat(result).isSameAs(expected);
    verify(linkRoundupScraper).scrape("https://ai4jvm.com");
    verifyNoInteractions(rssScraper, sitemapHtmlScraper, lumaApiScraper);
  }

  private ContentSource contentSource(
      String feedUrl,
      String baseUrl,
      String sitemapUrl,
      SourceType sourceType,
      ScrapeStrategy scrapeStrategy) {
    return new ContentSource(
        "src-1",
        "Test Source",
        baseUrl,
        feedUrl,
        sitemapUrl,
        sourceType,
        scrapeStrategy,
        true,
        null,
        null);
  }
}
