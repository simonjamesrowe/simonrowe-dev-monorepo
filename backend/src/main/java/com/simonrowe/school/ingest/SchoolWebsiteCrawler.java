package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.chat.StaffDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Crawls the school website.
 *
 * <p>Enumerates pages from the CMS's sitemap, which keeps the crawl bounded and avoids the
 * calendar's effectively infinite date-parameterised URL space. The cost is that a page missing
 * from the sitemap is invisible, and this sitemap does omit pages — every year-group page among
 * them, which is where the class teachers, the PE days and the weekly spellings live.
 *
 * <p>Two things close that, and they are separate on purpose. {@code
 * SchoolProperties.extraPageUrls} adds a short list of known-missing pages to
 * {@link #listPages()}. {@code SchoolIngestService} then follows same-host links out of those
 * pages, one hop — see {@code SchoolIngestService.shouldFollowLinksFrom} for why the hop starts
 * only there. This class's part in that is {@link CrawledPage#links()} and
 * {@link CrawledPage#canonicalUrl()}; the policy of what to follow is
 * {@link SchoolLinkFilter#isCrawlableWebsitePage}, and the queue is the ingest service's.
 *
 * <p>The crawl delay is honoured because the site's {@code robots.txt} asks for ten seconds and
 * this is a small school's hosting. It makes a full crawl slow — a couple of hundred pages is over
 * half an hour — which is why it runs on a schedule rather than on demand, and why the content
 * hash in {@link SchoolDocumentWriter} matters: the expensive part must not repeat for pages that
 * have not changed.
 */
@Component
public class SchoolWebsiteCrawler {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolWebsiteCrawler.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final Pattern SITEMAP_LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>");
  private static final int MAX_PAGES = 250;

  private final SchoolProperties properties;
  private final SchoolLinkFilter linkFilter;
  private final HttpClient httpClient;

  public SchoolWebsiteCrawler(
      final SchoolProperties properties, final SchoolLinkFilter linkFilter) {
    this.properties = properties;
    this.linkFilter = linkFilter;
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(
        HttpClient.Redirect.NORMAL).build();
  }

  /**
   * Every page the crawl should read: the school's sitemap, plus the configured extras.
   *
   * <p>The extras come <b>first</b>, deliberately. {@code MAX_PAGES} caps the whole list, and the
   * sitemap's tail is years of old sports reports while the extras are the year-group pages —
   * losing the latter to make room for the former would be exactly backwards. Today the sitemap
   * has 218 entries so nothing is dropped either way, but the ordering is what keeps that true
   * as the school adds news.
   *
   * <p>A sitemap that cannot be read still returns nothing, even when extras are configured.
   * {@code SchoolIngestService} treats an empty list as a source failure and records it, and
   * quietly returning seven pages instead would turn a visible outage into a crawl that appears
   * to have succeeded while skipping 96% of the site.
   *
   * @return the page URLs, capped to a sane maximum
   */
  public List<String> listPages() {
    final String sitemap = fetchText(properties.websiteBaseUrl() + "/googlesitemap.asp");
    if (sitemap == null) {
      LOG.warn("Could not read the school sitemap");
      return List.of();
    }
    final Set<String> urls = new LinkedHashSet<>(properties.extraPageUrls());
    final Matcher matcher = SITEMAP_LOC.matcher(sitemap);
    while (matcher.find() && urls.size() < MAX_PAGES) {
      final String url = matcher.group(1).trim();
      if (url.startsWith("http") && !url.contains("/calendar")) {
        urls.add(url);
      }
    }
    return List.copyOf(urls);
  }

  /**
   * Reads the CMS's page-updates feed: URL to real last-updated time.
   *
   * <p>Without this every crawled page is stamped with the ingest time, so a news item from
   * three years ago looks like it was published today — wrong in a citation, and actively
   * misleading to anything that reasons about recency.
   *
   * <p>The feed carries only the most recently changed pages (50 at the time of writing), so
   * this is a partial map by design. Pages absent from it keep whatever date they already had.
   *
   * @return page URL to publication time, empty if the feed could not be read
   */
  public java.util.Map<String, java.time.Instant> pageUpdateTimes() {
    final String xml = fetchText(properties.websiteBaseUrl() + "/feeds/pages.asp?pid=&lang=en");
    if (xml == null) {
      return java.util.Map.of();
    }
    final java.util.Map<String, java.time.Instant> dates = new java.util.LinkedHashMap<>();
    final Matcher item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(xml);
    while (item.find()) {
      final Matcher link = Pattern.compile("<link>(.*?)</link>").matcher(item.group(1));
      final Matcher date = Pattern.compile("<pubDate>(.*?)</pubDate>").matcher(item.group(1));
      if (!link.find() || !date.find()) {
        continue;
      }
      try {
        dates.put(
            org.jsoup.parser.Parser.unescapeEntities(link.group(1).trim(), false),
            java.time.ZonedDateTime.parse(date.group(1).trim(),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
      } catch (RuntimeException e) {
        LOG.debug("Unparseable feed entry: {}", e.getMessage());
      }
    }
    LOG.info("Read {} page update times from the CMS feed", dates.size());
    return java.util.Map.copyOf(dates);
  }

  /**
   * Fetches one page and extracts its readable text.
   *
   * @param url the page URL
   * @return the page, or null if it could not be read
   */
  public CrawledPage fetchPage(final String url) {
    final String html = fetchText(url);
    if (html == null) {
      return null;
    }
    final Document document = Jsoup.parse(html, url);
    // Read before the strip: <link rel="canonical"> lives in <head>, which survives, but taking
    // it first keeps this independent of what the strip removes.
    final String canonical = canonicalOf(document, url);
    document.select("script, style, nav, header, footer").remove();
    final String title = document.title();
    final String text = document.body() == null ? "" : document.body().text();
    // Links are taken from the STRIPPED document, and that is the single most effective filter
    // in the whole discovery path rather than a detail. This CMS emits semantic <nav>/<header>/
    // <footer>, and the site-wide navigation is ~60 same-host links repeated on every page —
    // measured on /year-six: 68 same-host links in the raw HTML, 12 once the furniture is gone.
    // Following the raw set would make one hop from any page equivalent to crawling the site.
    return new CrawledPage(url, canonical, title, text, html, linksIn(document));
  }

  /**
   * The page's own declared canonical URL.
   *
   * <p>Load-bearing for link following, because this CMS serves <b>every page under two
   * URLs</b>: a friendly path and a {@code /page/?title=...&pid=N} form. They are byte-identical
   * — verified on {@code /year-six-home-learning} and {@code /page/?title=Home+Learning&pid=158}
   * — and {@code SchoolIds.documentId} keys on the URL, so ingesting both stores the same text
   * twice and embeds it twice, which shows up as duplicate results rather than as an error.
   *
   * <p>Sitemap-only crawling never met this, since the sitemap lists one form. Discovery meets
   * it immediately: the only link from {@code /year-six} to its home-learning page is the
   * {@code /page/?} form, so the choice is not "canonicalise or avoid the ugly URLs" — it is
   * "canonicalise or lose the page".
   *
   * @param document the parsed page
   * @param url the URL it was fetched from, used when no canonical is declared
   * @return the canonical URL, never null
   */
  private String canonicalOf(final Document document, final String url) {
    final org.jsoup.nodes.Element link = document.selectFirst("link[rel=canonical][href]");
    if (link == null) {
      return url;
    }
    final String canonical = link.absUrl("href");
    if (canonical.isBlank()) {
      return url;
    }
    // Host-checked before it is trusted, because this is the one URL in the pipeline that the
    // fetched PAGE chooses rather than we do — and it does not stay internal. The canonical
    // becomes the document's sourceRef, which SchoolTools.renderChunk hands to the model as
    // the citation url= for any value starting https://, so a page declaring a canonical on
    // another host would put a link to that host in an answer written in the school's voice.
    // Link following is host-checked (SchoolLinkFilter.isCrawlableWebsitePage); this was the
    // one way in that was not. A wrong-host canonical is far more likely to be a CMS or
    // staging misconfiguration than an attack, and falling back to the URL we actually
    // fetched is the right answer to both.
    if (!linkFilter.isOnSchoolHost(canonical)) {
      LOG.warn("Ignoring off-host canonical {} declared by {}", canonical, url);
      return url;
    }
    return canonical;
  }

  private List<String> linksIn(final Document document) {
    final Set<String> links = new LinkedHashSet<>();
    for (org.jsoup.nodes.Element anchor : document.select("a[href]")) {
      // absUrl resolves against the base Jsoup.parse was given, so a relative href comes back
      // absolute. It does NOT filter: a mailto: comes back verbatim and "#top" comes back as
      // this page's own URL with the fragment still on it, so both are handled here.
      final String href = anchor.absUrl("href");
      if (!href.startsWith("http://") && !href.startsWith("https://")) {
        continue;
      }
      // The fragment is stripped at the point links are read rather than by each consumer.
      // The server never sees it, so "/year-six#top" and "/year-six" are one page — and left
      // on, an in-page anchor queues the page it was found on all over again.
      final int fragment = href.indexOf('#');
      final String url = fragment < 0 ? href : href.substring(0, fragment);
      // A link to the page it was found on is not a discovery. Common once the fragment is
      // gone, because "back to top" and the breadcrumb's own last crumb both become one.
      if (!url.isBlank() && !url.equals(document.location())) {
        links.add(url);
      }
    }
    return List.copyOf(links);
  }

  /**
   * Extracts published staff names from the school's staff page.
   *
   * <p>Populates {@link StaffDirectory}, which the name gate consults. Until this has run the
   * directory is empty and the gate treats every name as private — the fail-closed direction.
   *
   * @return the names found, empty if the page could not be read
   */
  public Set<String> fetchStaffNames() {
    final CrawledPage page = fetchPage(properties.staffListUrl());
    if (page == null) {
      return Set.of();
    }
    final Set<String> names = new LinkedHashSet<>();
    // Staff pages list people as "Mrs J Smith", "Dennis Irwin" or "Mr Irwin - Headteacher".
    // Both forms are captured; over-capturing here is harmless, because a name wrongly treated
    // as staff only ever matters if it also appears in restricted content, and the tier filter
    // is what keeps that out of a public answer, not this list.
    final Matcher titled = Pattern
        .compile("\\b(?:Mr|Mrs|Miss|Ms|Dr)\\.?\\s+(?:[A-Z]\\s+)?([A-Z][a-z]+)\\b")
        .matcher(page.text());
    while (titled.find()) {
      names.add(titled.group(1));
    }
    final Matcher full = Pattern
        .compile("\\b([A-Z][a-z]{1,15}\\s+[A-Z][a-z]{1,15})\\b")
        .matcher(page.text());
    while (full.find()) {
      names.add(full.group(1));
    }
    return Set.copyOf(names);
  }

  /**
   * Waits between fetches, as the site's robots.txt asks.
   *
   * @return false if the wait was interrupted, so a caller can stop cleanly
   */
  public boolean politePause() {
    try {
      Thread.sleep(Duration.ofSeconds(properties.crawlDelaySeconds()).toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Fetches a URL as text.
   *
   * <p>Package-private rather than private so a test can override it. {@link #listPages()} is
   * pure logic over two strings once the fetch is out of the way — which pages the crawl reaches
   * is exactly the thing that was wrong here, and it should not need a live school website to
   * assert.
   *
   * @param url the URL to fetch
   * @return the body, or null if it could not be read
   */
  String fetchText(final String url) {
    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "SimonRoweBot/1.0 (+https://simonrowe.dev)")
          .timeout(TIMEOUT)
          .GET()
          .build();
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debug("{} returned {}", url, response.statusCode());
        return null;
      }
      return response.body();
    } catch (IOException | IllegalArgumentException e) {
      LOG.debug("Could not fetch {}: {}", url, e.getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * One fetched page.
   *
   * @param url the URL it was fetched from
   * @param canonicalUrl the URL the page declares as its own, falling back to {@code url}. What
   *     a document is stored under, so two addresses for one page cannot become two documents
   * @param title the page title
   * @param text the readable text, with navigation, header and footer removed
   * @param html the raw markup, still including the navigation — {@code SchoolPdfExtractor}
   *     reads it, and a PDF linked from a footer is still a PDF worth having
   * @param links absolute URLs found in the page's <b>content</b>, navigation excluded
   */
  public record CrawledPage(
      String url, String canonicalUrl, String title, String text, String html,
      List<String> links) {
  }
}
