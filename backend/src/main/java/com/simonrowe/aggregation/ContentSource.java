package com.simonrowe.aggregation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An external site to aggregate news, events or blog posts from.
 *
 * @param categoryFilter optional RSS category to restrict ingestion to, matched
 *     case-insensitively against any of a feed entry's categories. Only consulted by
 *     {@link ScrapeStrategy#RSS}; {@code null} on every other strategy and on any source
 *     that wants a whole feed. Declared last so adding it appended to the existing
 *     positional call sites rather than inserting into them. Deliberately a required
 *     constructor argument with no defaulting overload: four places rebuild a
 *     {@code ContentSource} field-by-field (the two branches of
 *     {@code ContentAggregationAgent.runAggregation}, the admin PUT handler and
 *     {@code V007ResetClaudeBlogs}), and an overload would let each silently drop the
 *     filter — turning a section feed back into the publisher's entire output with
 *     nothing logged.
 */
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
    String lastError,
    String categoryFilter
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
