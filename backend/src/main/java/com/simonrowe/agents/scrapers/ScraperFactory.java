package com.simonrowe.agents.scrapers;

import com.simonrowe.aggregation.ContentSource;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ScraperFactory {

  private final RssScraper rssScraper;
  private final SitemapHtmlScraper sitemapHtmlScraper;
  private final LumaApiScraper lumaApiScraper;

  public ScraperFactory(RssScraper rssScraper,
      SitemapHtmlScraper sitemapHtmlScraper,
      LumaApiScraper lumaApiScraper) {
    this.rssScraper = rssScraper;
    this.sitemapHtmlScraper = sitemapHtmlScraper;
    this.lumaApiScraper = lumaApiScraper;
  }

  public List<ScrapedContent> scrape(ContentSource source) {
    boolean isEvent = source.sourceType() == ContentSource.SourceType.EVENTS;
    return switch (source.scrapeStrategy()) {
      case RSS -> rssScraper.scrape(source.feedUrl(), isEvent);
      case SITEMAP_HTML -> sitemapHtmlScraper.scrape(source.sitemapUrl());
      case HTML -> sitemapHtmlScraper.scrapeEventsPage(source.baseUrl());
      case HTML_LISTING -> sitemapHtmlScraper.scrapeListingPage(source.baseUrl());
      case LUMA -> lumaApiScraper.scrape(source.feedUrl());
    };
  }
}
