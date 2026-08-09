package com.simonrowe.aggregation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "content_sources")
public record ContentSource(
    @Id String id,
    @Indexed(unique = true) String name,
    String baseUrl,
    String feedUrl,
    String sitemapUrl,
    SourceType sourceType,
    ScrapeStrategy scrapeStrategy,
    @Indexed boolean active,
    Instant lastFetchedAt,
    String lastError
) {

  public enum SourceType {
    BLOG,
    NEWS,
    EVENTS
  }

  public enum ScrapeStrategy {
    RSS,
    SITEMAP_HTML,
    HTML,
    HTML_LISTING,
    LUMA,
    LINK_ROUNDUP
  }
}
