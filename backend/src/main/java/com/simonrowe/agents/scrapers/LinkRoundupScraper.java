package com.simonrowe.agents.scrapers;

import com.simonrowe.aggregation.AggregatedArticleRepository;
import java.net.URI;
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

  private static final long DELAY_BETWEEN_REQUESTS_MS = 1000;

  private final SitemapHtmlScraper htmlScraper;
  private final AggregatedArticleRepository articleRepository;

  /**
   * Takes a repository, unlike every other scraper. Without a pre-fetch dedup check the
   * 40-item cap means every scheduled run would re-request all forty linked pages across
   * a dozen third-party sites purely to discover we already hold them. The agent's own
   * dedup remains the authority; this is an optimisation only.
   */
  public LinkRoundupScraper(
      final SitemapHtmlScraper htmlScraper,
      final AggregatedArticleRepository articleRepository) {
    this.htmlScraper = htmlScraper;
    this.articleRepository = articleRepository;
  }

  public List<ScrapedContent> scrape(final String listingUrl) {
    return follow(fetchLinks(listingUrl));
  }

  List<ScrapedContent> follow(final List<RoundupLink> links) {
    List<ScrapedContent> results = new ArrayList<>();
    boolean firstFetch = true;
    for (RoundupLink link : links) {
      if (articleRepository.existsByOriginalUrl(link.url())) {
        continue;
      }
      if (!firstFetch) {
        try {
          Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      firstFetch = false;
      results.add(toContent(link));
    }
    log.info("Followed {} of {} roundup links", results.size(), links.size());
    return results;
  }

  /**
   * The target page's own article where it can be scraped, otherwise an item built from
   * the curator's title and summary. Those summaries run 150-250 characters, comfortably
   * over the 50-character floor below which classification is skipped, so a fallback item
   * is still summarised and indexed — it simply has no image or date of its own.
   *
   * <p>The detail's own URL is replaced with the link URL. That is the URL the card should
   * point at, it is the key {@code follow} already checked, and it keeps the stored
   * {@code originalUrl} equal to the dedup key: {@code scrapeArticlePage} normalises again
   * internally, which strips a query and re-encodes {@code %}, so without this a
   * query-bearing or percent-encoded target would be re-fetched on every run forever.
   */
  ScrapedContent toContent(final RoundupLink link) {
    ScrapedContent detail = htmlScraper.scrapeArticlePagePublic(link.url());
    if (detail != null) {
      return new ScrapedContent(
          detail.title(), link.url(), detail.content(),
          detail.publishedDate(), detail.author(), detail.imageUrl(), detail.isEvent(),
          detail.venue(), detail.location());
    }
    log.info("Target page unavailable, using curated text for: {}", link.url());
    return new ScrapedContent(
        link.title(), link.url(), link.summary(), null, null, null, false);
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
      String url = normalizeTarget(rawUrl);
      String title = anchor.text().trim();
      if (title.isEmpty() || !seenUrls.add(url)) {
        continue;
      }
      links.add(new RoundupLink(title, url, extractSummary(item, title)));
    }
    return links;
  }

  /**
   * Normalises a roundup target, keeping any query string.
   *
   * <p>Roundup targets are arbitrary third-party URLs where the query can <em>be</em> the
   * resource's identity ({@code watch?v=…}, {@code ?p=123}). The shared normaliser drops
   * the query, which is right for the same-host sitemap and listing URLs it was written
   * for and wrong here: two talk links on one path would collapse to one item and the card
   * would link to a generic page. It is deliberately not changed — every existing
   * {@code SITEMAP_HTML}/{@code HTML_LISTING} source's stored {@code originalUrl} depends
   * on its current behaviour.
   *
   * <p>Query-less URLs still go through the shared rules, so the common case stays
   * byte-identical to what {@code scrapeArticlePage} returns and dedup lines up. For a
   * query-bearing target the two would diverge, which is why {@link #toContent} stores the
   * link URL rather than the detail's URL — without that, such items would be re-fetched
   * on every six-hourly run forever.
   *
   * @param rawUrl the absolute target URL as written in the roundup
   * @return the dedup key and stored {@code originalUrl} for that target
   */
  private static String normalizeTarget(final String rawUrl) {
    try {
      String query = URI.create(rawUrl).getRawQuery();
      if (query != null && !query.isBlank()) {
        return rawUrl;
      }
    } catch (Exception e) {
      // Unparseable here too; let the shared normaliser apply its own lenient fallback.
    }
    return SitemapHtmlScraper.normalizeUrl(rawUrl);
  }

  /** The item's text with the anchor text and the separating em-dash removed. */
  private String extractSummary(final Element item, final String title) {
    String text = item.text().trim();
    if (text.startsWith(title)) {
      text = text.substring(title.length());
    }
    return text.replaceFirst("^[\\s\\u2013\\u2014-]+", "").trim();
  }
}
