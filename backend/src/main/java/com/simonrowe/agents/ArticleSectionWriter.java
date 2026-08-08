package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.agents.scrapers.SitemapHtmlScraper;
import com.simonrowe.aggregation.AggregatedArticle;
import java.util.List;
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
    try {
      String prompt = String.format(
          SECTION_PROMPT, article.title(), article.sourceName(), sourceText);
      String body = ai.withLlm(model)
          .respond(List.of(new UserMessage(prompt)))
          .getContent();
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(), body, false);
    } catch (Exception e) {
      LOG.warn("Failed to summarise '{}', using stored summary: {}",
          article.title(), e.getMessage());
      return new DigestSection(
          article.id(), article.title(), article.originalUrl(),
          article.summary(), true);
    }
  }

  private String sourceTextFor(final AggregatedArticle article) {
    ScrapedContent scraped =
        scraper.scrapeArticlePagePublic(article.originalUrl());
    if (scraped != null && isUsable(scraped.content())) {
      return truncate(scraped.content());
    }
    LOG.info("Scrape returned nothing usable for '{}', "
        + "falling back to stored content", article.title());
    if (isUsable(article.fullContent())) {
      return truncate(article.fullContent());
    }
    return truncate(article.summary());
  }

  private static boolean isUsable(final String text) {
    return text != null && !text.isBlank();
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
