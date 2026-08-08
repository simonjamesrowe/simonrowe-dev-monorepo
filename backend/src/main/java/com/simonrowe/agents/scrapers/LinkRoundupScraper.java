package com.simonrowe.agents.scrapers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scrapes a curated link roundup: a page whose value is the list of <em>external</em>
 * articles it points at, rather than any article of its own.
 *
 * <p>Built for ai4jvm.com, whose news lives in a hand-maintained {@code div.news-bar}
 * holding one {@code li} per headline: a single anchor to another site, an em-dash, and
 * a hand-written summary. There is no feed, and the sitemap lists only the homepage.
 *
 * <p>The existing {@code HTML_LISTING} strategy cannot be used: {@code isArticleLink}
 * requires each link's host to equal the listing page's host, so every headline here is
 * rejected and the scrape returns zero items with no error. That rule is load-bearing for
 * the other listing sources and is deliberately left alone.
 */
@Component
public class LinkRoundupScraper {

  private static final Logger log = LoggerFactory.getLogger(LinkRoundupScraper.class);

  private static final int TIMEOUT_MS = 15000;
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/131.0.0.0 Safari/537.36";

  private static final String PRIMARY_SELECTOR = ".news-bar li";
  private static final String FALLBACK_SELECTOR = "#news li";

  /**
   * Deliberately above {@code SitemapHtmlScraper.MAX_ARTICLES} (20). The roundup holds 29
   * items ordered newest-first, and dedup happens after scraping — so with a cap of 20 the
   * tail would be re-skipped on every run and never ingested at all.
   */
  static final int MAX_ITEMS = 40;

  /** One curated headline: where it points, and how the curator framed it. */
  record RoundupLink(String title, String url, String summary) {
  }

  public List<ScrapedContent> scrape(final String listingUrl) {
    List<RoundupLink> links = fetchLinks(listingUrl);
    return links.stream().map(LinkRoundupScraper::toCuratedContent).toList();
  }

  List<RoundupLink> fetchLinks(final String listingUrl) {
    try {
      Document doc = Jsoup.connect(listingUrl)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();
      List<RoundupLink> links = extractLinks(doc);
      log.info("Found {} roundup links on {}", links.size(), listingUrl);
      return links;
    } catch (Exception e) {
      log.error("Failed to scrape link roundup: {}", listingUrl, e);
      return List.of();
    }
  }

  /**
   * An item with no anchor, or with more than one, is skipped rather than guessed at:
   * the roundup's own format is exactly one link per headline, so anything else is
   * either prose or a structure this scraper does not understand.
   */
  List<RoundupLink> extractLinks(final Document doc) {
    Elements items = doc.select(PRIMARY_SELECTOR);
    if (items.isEmpty()) {
      items = doc.select(FALLBACK_SELECTOR);
    }

    List<RoundupLink> links = new ArrayList<>();
    Set<String> seenUrls = new LinkedHashSet<>();
    for (Element item : items) {
      if (links.size() >= MAX_ITEMS) {
        break;
      }
      Elements anchors = item.select("a[href]");
      if (anchors.size() != 1) {
        continue;
      }
      Element anchor = anchors.first();
      String rawUrl = anchor.absUrl("href");
      if (!rawUrl.startsWith("http")) {
        continue;
      }
      // Normalised with the article scraper's own rules so the URL recorded here
      // matches the one it would return, and dedup on originalUrl lines up.
      String url = SitemapHtmlScraper.normalizeUrl(rawUrl);
      String title = anchor.text().trim();
      if (title.isEmpty() || !seenUrls.add(url)) {
        continue;
      }
      links.add(new RoundupLink(title, url, extractSummary(item, title)));
    }
    return links;
  }

  /** The item's text with the anchor text and the separating em-dash removed. */
  private String extractSummary(final Element item, final String title) {
    String text = item.text().trim();
    if (text.startsWith(title)) {
      text = text.substring(title.length());
    }
    return text.replaceFirst("^[\\s\\u2013\\u2014-]+", "").trim();
  }

  private static ScrapedContent toCuratedContent(final RoundupLink link) {
    return new ScrapedContent(
        link.title(), link.url(), link.summary(), null, null, null, false);
  }
}
