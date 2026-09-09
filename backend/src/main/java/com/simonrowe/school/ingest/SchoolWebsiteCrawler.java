package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.chat.StaffDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>Enumerates pages from the CMS's sitemap rather than following links, which keeps the crawl
 * bounded and avoids the calendar's effectively infinite date-parameterised URL space.
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
  private final HttpClient httpClient;

  public SchoolWebsiteCrawler(final SchoolProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(
        HttpClient.Redirect.NORMAL).build();
  }

  /**
   * Every page URL listed in the school's sitemap.
   *
   * @return the page URLs, capped to a sane maximum
   */
  public List<String> listPages() {
    final String sitemap = fetchText(properties.websiteBaseUrl() + "/googlesitemap.asp");
    if (sitemap == null) {
      LOG.warn("Could not read the school sitemap");
      return List.of();
    }
    final List<String> urls = new ArrayList<>();
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
    document.select("script, style, nav, header, footer").remove();
    final String title = document.title();
    final String text = document.body() == null ? "" : document.body().text();
    return new CrawledPage(url, title, text, html);
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

  private String fetchText(final String url) {
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
   * @param url the URL fetched
   * @param title the page title
   * @param text readable text with chrome stripped
   * @param html the raw HTML, kept so PDF links can be extracted without a second fetch
   */
  public record CrawledPage(String url, String title, String text, String html) {
  }
}
