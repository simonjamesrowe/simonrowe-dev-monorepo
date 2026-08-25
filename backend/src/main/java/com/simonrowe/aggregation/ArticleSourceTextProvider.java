package com.simonrowe.aggregation;

import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the best available body text for one aggregated article, for any consumer that
 * needs to say something substantive about it.
 *
 * <p>Re-scraping rather than trusting the stored {@code fullContent} matters because that
 * field's depth varies — full page text for HTML and sitemap sources, often a bare feed
 * snippet for RSS ones — and it may be weeks stale.
 *
 * <p>Extracted from {@code ArticleSectionWriter}, which was its only consumer until the
 * on-demand article summariser needed exactly the same cascade. The length floors below
 * encode hard-won knowledge about paywall and consent-wall interstitials; copying them
 * into a second class would fork that knowledge.
 */
@Component
public class ArticleSourceTextProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArticleSourceTextProvider.class);

  private static final int MAX_SOURCE_CHARS = 12_000;

  /**
   * Minimum length, in characters, for a source text (fresh scrape or stored
   * {@code fullContent}) to be trusted on its own merits. Below this a page
   * is plausibly a paywall or consent-wall interstitial rather than the
   * article body — the same failure mode
   * {@code ContentAggregationAgent}'s classifier guards against with its own
   * length floor.
   */
  private static final int MIN_USABLE_SOURCE_CHARS = 500;

  /**
   * Hard floor, in characters, below which even the longest available source
   * (fresh scrape, stored {@code fullContent} or stored summary) is too thin
   * to summarise honestly. Below this the model is not asked to write about
   * the article at all.
   *
   * <p>Public because it is the caller's decision what to do when the floor is not
   * cleared: the digest publishes the stored summary as-is, while the on-demand
   * summariser records a non-retryable {@code INSUFFICIENT_SOURCE_TEXT} failure.
   */
  public static final int HARD_MIN_SOURCE_CHARS = 200;

  private final SitemapHtmlScraper scraper;

  public ArticleSourceTextProvider(final SitemapHtmlScraper scraper) {
    this.scraper = scraper;
  }

  /**
   * Picks the best available source text: the fresh scrape if it clears
   * {@link #MIN_USABLE_SOURCE_CHARS}, else the stored {@code fullContent} if
   * that clears it, else whichever of the three available sources (scrape,
   * stored content, stored summary) is longest — which may still be below
   * {@link #HARD_MIN_SOURCE_CHARS}, in which case the caller is expected to
   * decline to call the model.
   *
   * @param article the article to resolve text for
   * @return the source text, truncated to {@link #MAX_SOURCE_CHARS}; never null, possibly
   *     empty and possibly under {@link #HARD_MIN_SOURCE_CHARS}
   */
  public String sourceTextFor(final AggregatedArticle article) {
    ScrapedContent scraped =
        scraper.scrapeArticlePagePublic(article.originalUrl());
    String scrapedText = scraped != null ? scraped.content() : null;
    if (clearsFloor(scrapedText, MIN_USABLE_SOURCE_CHARS)) {
      return truncate(scrapedText);
    }
    LOG.info("Scrape returned nothing usable for '{}', "
        + "falling back to stored content", article.title());
    String fullContent = article.fullContent();
    if (clearsFloor(fullContent, MIN_USABLE_SOURCE_CHARS)) {
      return truncate(fullContent);
    }
    return truncate(longestOf(scrapedText, fullContent, article.summary()));
  }

  /** Whether the text is long enough to be worth sending to a model at all. */
  public static boolean clearsHardFloor(final String text) {
    return clearsFloor(text, HARD_MIN_SOURCE_CHARS);
  }

  private static boolean clearsFloor(final String text, final int floor) {
    return text != null && text.length() >= floor;
  }

  private static String longestOf(final String... candidates) {
    String longest = "";
    for (String candidate : candidates) {
      if (candidate != null && candidate.length() > longest.length()) {
        longest = candidate;
      }
    }
    return longest;
  }

  private static String truncate(final String text) {
    if (text == null) {
      return "";
    }
    return text.length() > MAX_SOURCE_CHARS
        ? text.substring(0, MAX_SOURCE_CHARS)
        : text;
  }
}
