package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns one favourited article into one digest section: re-scrapes the source
 * for the freshest and fullest text, then asks the model for a few paragraphs
 * about it.
 *
 * <p>Re-scraping rather than trusting the stored {@code fullContent} matters
 * because that field's depth varies — full page text for HTML and sitemap
 * sources, often a bare feed snippet for RSS ones — and it may be weeks stale.
 */
@Component
public class ArticleSectionWriter {

  private static final Logger LOG =
      LoggerFactory.getLogger(ArticleSectionWriter.class);

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
   * the article at all — the stored summary is published as-is instead.
   */
  private static final int HARD_MIN_SOURCE_CHARS = 200;

  /**
   * Matches an HTML/XML-like tag (e.g. {@code <script>}, {@code <img src=x>})
   * without flagging a bare comparison such as "a < b" or "5<10", which have
   * no closing {@code >}.
   */
  private static final Pattern HTML_TAG =
      Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");

  private static final String SECTION_PROMPT = """
      You are Simon Rowe, writing one section of your weekly digest about an \
      article you saved this week.

      Write 2-3 paragraphs summarising what this piece actually says — the \
      substance, not a description of the article. Then finish with one short \
      sentence, on its own line, beginning "Why this caught my eye:" giving \
      the angle that makes it worth someone's time.

      Write in first person, in Markdown. Do NOT write any heading — the \
      heading and the link are added separately. Do NOT repeat the title.

      Title: %s
      Source: %s

      Article text:
      %s
      """;

  private final SitemapHtmlScraper scraper;
  private final Ai ai;
  private final String model;

  public ArticleSectionWriter(
      final SitemapHtmlScraper scraper,
      final Ai ai,
      @Value("${aggregation.digest.model}") final String model) {
    this.scraper = scraper;
    this.ai = ai;
    this.model = model;
  }

  /**
   * Builds the digest section for a single article.
   *
   * @param article the favourited article
   * @return the section; never null, with {@code fallback} set when the model
   *     call failed and the stored summary was used instead
   */
  public DigestSection write(final AggregatedArticle article) {
    String sourceText = sourceTextFor(article);
    if (sourceText.length() < HARD_MIN_SOURCE_CHARS) {
      LOG.warn("No usable source text for '{}' — fresh scrape, stored "
          + "content and stored summary are all under {} characters; "
          + "publishing the stored summary without calling the model",
          article.title(), HARD_MIN_SOURCE_CHARS);
      return fallbackSection(article);
    }
    try {
      String prompt = String.format(
          SECTION_PROMPT, article.title(), article.sourceName(), sourceText);
      String body = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt)))
          .getContent();
      if (body == null || body.isBlank()) {
        LOG.warn("Empty completion summarising '{}', using stored summary",
            article.title());
        return fallbackSection(article);
      }
      if (containsHtml(body)) {
        LOG.warn("Model output for '{}' contained an HTML tag, "
            + "using stored summary instead", article.title());
        return fallbackSection(article);
      }
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(), body, false);
    } catch (Exception e) {
      LOG.warn("Failed to summarise '{}', using stored summary: {}",
          article.title(), e.getMessage());
      return fallbackSection(article);
    }
  }

  private static DigestSection fallbackSection(final AggregatedArticle article) {
    return new DigestSection(
        article.id(), article.title(), article.originalUrl(),
        article.summary(), true);
  }

  /**
   * Picks the best available source text: the fresh scrape if it clears
   * {@link #MIN_USABLE_SOURCE_CHARS}, else the stored {@code fullContent} if
   * that clears it, else whichever of the three available sources (scrape,
   * stored content, stored summary) is longest — which may still be below
   * the floor, in which case {@link #write} declines to call the model.
   */
  private String sourceTextFor(final AggregatedArticle article) {
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

  private static boolean containsHtml(final String text) {
    return HTML_TAG.matcher(text).find();
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
