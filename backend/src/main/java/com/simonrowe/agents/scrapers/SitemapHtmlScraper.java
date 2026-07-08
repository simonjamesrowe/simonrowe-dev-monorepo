package com.simonrowe.agents.scrapers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SitemapHtmlScraper {

  private static final Logger log = LoggerFactory.getLogger(SitemapHtmlScraper.class);
  private static final int TIMEOUT_MS = 15000;
  private static final int MAX_ARTICLES = 20;
  private static final long DELAY_BETWEEN_REQUESTS_MS = 1000;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/131.0.0.0 Safari/537.36";

  private static final Pattern DATE_HEADER_PATTERN = Pattern.compile(
      "(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*",
      Pattern.CASE_INSENSITIVE);

  private static final DateTimeFormatter DAY_MONTH_FORMATTER =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  // Fix 5: regex for visible-text date extraction
  private static final Pattern TEXT_DATE_PATTERN = Pattern.compile(
      "(?:(\\d{1,2})\\s+(January|February|March|April|May|June|July|August|September"
          + "|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
          + "\\s+(\\d{4}))"
          + "|(?:(January|February|March|April|May|June|July|August|September"
          + "|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
          + "\\s+(\\d{1,2}),?\\s+(\\d{4}))",
      Pattern.CASE_INSENSITIVE);

  public List<ScrapedContent> scrape(String sitemapUrl) {
    List<ScrapedContent> results = new ArrayList<>();
    Set<String> seenTitles = new LinkedHashSet<>();
    try {
      Document sitemap = Jsoup.connect(sitemapUrl)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();
      Elements urls = sitemap.select("url > loc");

      int count = 0;
      for (Element urlElement : urls) {
        if (count >= MAX_ARTICLES) {
          break;
        }
        String articleUrl = urlElement.text().trim();
        if (articleUrl.isEmpty() || !looksLikeArticle(articleUrl)) {
          continue;
        }
        try {
          ScrapedContent content = scrapeArticlePage(articleUrl);
          if (content != null && !seenTitles.contains(content.title())) {
            seenTitles.add(content.title());
            results.add(content);
            count++;
          }
        } catch (Exception e) {
          log.warn("Failed to scrape article: {}", articleUrl, e);
        }
        try {
          Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      log.info("Scraped {} articles from sitemap: {}", results.size(), sitemapUrl);
    } catch (Exception e) {
      log.error("Failed to scrape sitemap: {}", sitemapUrl, e);
    }
    return results;
  }

  public List<ScrapedContent> scrapeListingPage(String listingUrl) {
    List<ScrapedContent> results = new ArrayList<>();
    Set<String> seenTitles = new LinkedHashSet<>();
    try {
      Document doc = Jsoup.connect(listingUrl)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();

      Set<String> articleUrls = new LinkedHashSet<>();
      for (Element anchor : doc.select("a[href]")) {
        String href = anchor.absUrl("href");
        if (isArticleLink(href, listingUrl)) {
          // Fix 2: normalize before adding to de-duplicate
          articleUrls.add(normalizeUrl(href));
        }
      }

      log.info("Found {} candidate article links on listing page: {}",
          articleUrls.size(), listingUrl);

      int count = 0;
      for (String articleUrl : articleUrls) {
        if (count >= MAX_ARTICLES) {
          break;
        }
        try {
          ScrapedContent content = scrapeArticlePage(articleUrl);
          if (content != null && !seenTitles.contains(content.title())) {
            seenTitles.add(content.title());
            results.add(content);
            count++;
          }
        } catch (Exception e) {
          log.warn("Failed to scrape article: {}", articleUrl, e);
        }
        try {
          Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      log.info("Scraped {} articles from listing page: {}", results.size(), listingUrl);
    } catch (Exception e) {
      log.error("Failed to scrape listing page: {}", listingUrl, e);
    }
    return results;
  }

  private boolean isArticleLink(String href, String listingUrl) {
    if (href == null || href.isEmpty()) {
      return false;
    }
    try {
      URI hrefUri = new URI(href);
      URI listingUri = new URI(listingUrl);

      if (!hrefUri.getHost().equals(listingUri.getHost())) {
        return false;
      }

      String path = hrefUri.getPath();
      if (path == null || path.isEmpty()) {
        return false;
      }

      String[] segments = path.split("/");
      long nonEmptySegments = java.util.Arrays.stream(segments)
          .filter(s -> !s.isEmpty())
          .count();
      if (nonEmptySegments < 2) {
        return false;
      }

      if (href.contains("#")) {
        return false;
      }
      if (href.contains("?page=")) {
        return false;
      }

      String listingPath = listingUri.getPath();
      if (path.equals(listingPath) || path.equals(listingPath + "/")) {
        return false;
      }

      String lastSegment = segments[segments.length - 1];
      if (lastSegment.isEmpty() && segments.length > 1) {
        lastSegment = segments[segments.length - 2];
      }
      if (lastSegment.equals("category") || lastSegment.equals("tag")
          || lastSegment.equals("author") || lastSegment.equals("page")) {
        return false;
      }

      String secondSegment = "";
      for (String s : segments) {
        if (!s.isEmpty()) {
          secondSegment = s;
          break;
        }
      }
      if (secondSegment.equals("category") || secondSegment.equals("tag")
          || secondSegment.equals("author") || secondSegment.equals("page")) {
        return false;
      }

      // Fix 3: filter junk/utility pages
      String lowerPath = path.toLowerCase();
      if (lowerPath.contains("/privacy") || lowerPath.contains("/terms")
          || lowerPath.contains("/legal") || lowerPath.contains("/cookie")
          || lowerPath.contains("/contact") || lowerPath.contains("/about")
          || lowerPath.contains("/careers") || lowerPath.contains("/login")
          || lowerPath.contains("/signup") || lowerPath.contains("/pricing")) {
        return false;
      }

      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // Fix 2: URL normalization helper
  private static String normalizeUrl(String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = new URI(url);
      String normalized = new URI(
          uri.getScheme(),
          uri.getHost().toLowerCase(),
          uri.getPath().replaceAll("/+$", ""),
          null, null).toString();
      return normalized;
    } catch (Exception e) {
      return url.replaceAll("[?#].*$", "").replaceAll("/+$", "");
    }
  }

  public List<ScrapedContent> scrapeEventsPage(String pageUrl) {
    List<ScrapedContent> results = new ArrayList<>();
    try {
      Document doc = Jsoup.connect(pageUrl)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();

      if (pageUrl.contains("tessl.io")) {
        results.addAll(scrapeTesslEvents(doc));
      } else {
        results.addAll(scrapeGenericEvents(doc));
      }

      log.info("Scraped {} events from page: {}", results.size(), pageUrl);
    } catch (Exception e) {
      log.error("Failed to scrape events page: {}", pageUrl, e);
    }
    return results;
  }

  private List<ScrapedContent> scrapeTesslEvents(Document doc) {
    List<ScrapedContent> results = new ArrayList<>();
    int currentYear = Year.now().getValue();
    String lastDateStr = null;

    Elements children = doc.select(
        "[class*=timeline], [class*=event], [class*=schedule], "
            + "section, main, [class*=content]")
        .first() != null
        ? doc.select(
            "[class*=timeline], [class*=event], [class*=schedule], "
                + "section, main, [class*=content]")
            .first().children()
        : doc.body().children();

    for (Element el : doc.getAllElements()) {
      if (results.size() >= MAX_ARTICLES) {
        break;
      }

      String text = el.ownText().trim();
      Matcher dateMatcher = DATE_HEADER_PATTERN.matcher(text);
      if (dateMatcher.find() && text.length() < 40) {
        lastDateStr = dateMatcher.group(1) + " " + dateMatcher.group(2) + " " + currentYear;
        continue;
      }

      Element titleEl = el.selectFirst("h2, h3, h4");
      if (titleEl == null) {
        continue;
      }
      String title = titleEl.text().trim();
      if (title.isEmpty() || title.length() < 10) {
        continue;
      }

      String link = "";
      Element anchor = el.selectFirst("a[href]");
      if (anchor != null) {
        link = anchor.absUrl("href");
      }
      if (link.isEmpty()) {
        if (el.tagName().equals("a")) {
          link = el.absUrl("href");
        }
      }
      if (link.isEmpty()) {
        continue;
      }

      final String eventUrl = link;
      if (results.stream().anyMatch(r -> r.url().equals(eventUrl))) {
        continue;
      }

      Instant eventDate = null;
      if (lastDateStr != null) {
        eventDate = parseDayMonthYear(lastDateStr);
      }
      Element timeEl = el.selectFirst("time[datetime]");
      if (timeEl != null) {
        Instant parsed = parseDateTime(timeEl.attr("datetime"));
        if (parsed != null) {
          eventDate = parsed;
        }
      }

      String venue = null;
      String location = null;
      Element venueEl = el.selectFirst(
          "[class*=venue], [class*=location], [class*=place]");
      if (venueEl != null) {
        venue = venueEl.text().trim();
      }
      Elements metaEls = el.select("p, span, div");
      for (Element meta : metaEls) {
        String metaText = meta.ownText().trim();
        if (metaText.contains("London") || metaText.contains("Virtual")
            || metaText.contains("Online") || metaText.contains("Berlin")
            || metaText.contains("Barcelona") || metaText.contains("Amsterdam")
            || metaText.contains("San Francisco") || metaText.contains("New York")) {
          if (location == null) {
            location = metaText;
          }
        }
      }

      String imageUrl = null;
      Element img = el.selectFirst("img[src]");
      if (img != null) {
        imageUrl = img.absUrl("src");
      }

      String description = el.text();

      results.add(new ScrapedContent(
          title, link, description, eventDate, null, imageUrl, true, venue, location));
    }
    return results;
  }

  private List<ScrapedContent> scrapeGenericEvents(Document doc) {
    List<ScrapedContent> results = new ArrayList<>();

    Elements eventElements = doc.select(
        "a[href*=event], [class*=event-card], [class*=event-item], article");
    for (Element el : eventElements) {
      if (results.size() >= MAX_ARTICLES) {
        break;
      }

      Element titleEl = el.selectFirst("h2, h3, h4, [class*=title]");
      String title = titleEl != null ? titleEl.text() : el.text();
      if (title.isEmpty() || title.length() < 10) {
        continue;
      }

      String link = el.absUrl("href");
      if (link.isEmpty()) {
        Element anchor = el.selectFirst("a[href]");
        if (anchor != null) {
          link = anchor.absUrl("href");
        }
      }
      if (link.isEmpty()) {
        continue;
      }

      String description = el.text();

      Instant eventDate = null;
      Element timeEl = el.selectFirst("time[datetime]");
      if (timeEl != null) {
        eventDate = parseDateTime(timeEl.attr("datetime"));
      }

      String imageUrl = null;
      Element img = el.selectFirst("img[src]");
      if (img != null) {
        imageUrl = img.absUrl("src");
      }

      results.add(new ScrapedContent(
          title, link, description, eventDate, null, imageUrl, true));
    }
    return results;
  }

  public ScrapedContent scrapeArticlePagePublic(String url) {
    try {
      return scrapeArticlePage(url);
    } catch (Exception e) {
      log.warn("Failed to scrape article page: {}", url, e);
      return null;
    }
  }

  private ScrapedContent scrapeArticlePage(String url) throws Exception {
    Document doc = Jsoup.connect(url)
        .timeout(TIMEOUT_MS)
        .userAgent(USER_AGENT)
        .get();

    String title = doc.title();
    Element ogTitle = doc.selectFirst("meta[property=og:title]");
    if (ogTitle != null && !ogTitle.attr("content").isEmpty()) {
      title = ogTitle.attr("content");
    }
    
    if (title != null) {
      title = title.replaceAll("(?i)\\s*\\|\\s*Claude\\s*by\\s*Anthropic$", "");
      title = title.replaceAll("(?i)\\s*\\|\\s*Claude$", "");
    }

    String content = extractArticleContent(doc);
    if (title == null || title.isEmpty() || content.isEmpty()) {
      return null;
    }

    String author = null;
    Element authorMeta = doc.selectFirst("meta[name=author]");
    if (authorMeta != null) {
      author = authorMeta.attr("content");
    }

    String imageUrl = null;
    Element ogImage = doc.selectFirst("meta[property=og:image]");
    if (ogImage != null && !ogImage.attr("content").isEmpty()) {
      imageUrl = ogImage.attr("content");
    } else {
      Element img = doc.select("article img[src], main img[src], img[src]").stream()
          .filter(e -> {
            String src = e.absUrl("src").toLowerCase();
            return !src.endsWith(".svg") && !src.contains("icon") && !src.contains("logo");
          })
          .findFirst().orElse(null);
      if (img != null) {
        imageUrl = img.absUrl("src");
      }
    }

    // Fix 2: normalize the URL stored in ScrapedContent
    return new ScrapedContent(title, normalizeUrl(url), content,
        extractPublishedDate(doc), author, imageUrl, false);
  }

  public Instant extractPublishedDateFromUrl(String url) {
    try {
      Document doc = Jsoup.connect(url)
          .timeout(TIMEOUT_MS)
          .userAgent(USER_AGENT)
          .get();
      return extractPublishedDate(doc);
    } catch (Exception e) {
      log.warn("Failed to fetch page for date extraction: {}", url, e);
      return null;
    }
  }

  public Instant extractPublishedDate(Document doc) {
    Element pubTimeMeta = doc.selectFirst("meta[property=article:published_time]");
    if (pubTimeMeta != null && !pubTimeMeta.attr("content").isEmpty()) {
      Instant parsed = parseInstantOrDate(pubTimeMeta.attr("content"));
      if (parsed != null) {
        return parsed;
      }
    }

    Element ogPubTime = doc.selectFirst("meta[property=og:article:published_time]");
    if (ogPubTime != null && !ogPubTime.attr("content").isEmpty()) {
      Instant parsed = parseInstantOrDate(ogPubTime.attr("content"));
      if (parsed != null) {
        return parsed;
      }
    }

    Element timeElement = doc.selectFirst("time[datetime]");
    if (timeElement != null && !timeElement.attr("datetime").isEmpty()) {
      Instant parsed = parseDateTime(timeElement.attr("datetime"));
      if (parsed != null) {
        return parsed;
      }
    }

    Element dateMeta = doc.selectFirst("meta[name=date]");
    if (dateMeta != null && !dateMeta.attr("content").isEmpty()) {
      Instant parsed = parseDateOnly(dateMeta.attr("content"));
      if (parsed != null) {
        return parsed;
      }
    }

    Instant jsonLdDate = extractDateFromJsonLd(doc);
    if (jsonLdDate != null) {
      return jsonLdDate;
    }

    Element datePublished = doc.selectFirst("[itemprop=datePublished]");
    if (datePublished != null) {
      String val = datePublished.hasAttr("content")
          ? datePublished.attr("content") : datePublished.attr("datetime");
      if (!val.isEmpty()) {
        Instant parsed = parseInstantOrDate(val);
        if (parsed != null) {
          return parsed;
        }
      }
    }

    // Fix 5: visible-text regex fallback in header area
    String headerText = extractHeaderText(doc);
    if (headerText != null) {
      Instant textDate = extractDateFromText(headerText);
      if (textDate != null) {
        return textDate;
      }
    }

    return null;
  }

  private Instant extractDateFromJsonLd(Document doc) {
    Elements scripts = doc.select("script[type=application/ld+json]");
    for (Element script : scripts) {
      try {
        JsonNode node = MAPPER.readTree(script.data());
        JsonNode dateNode = node.path("datePublished");
        if (!dateNode.isMissingNode() && !dateNode.asText().isEmpty()) {
          Instant parsed = parseInstantOrDate(dateNode.asText());
          if (parsed != null) {
            return parsed;
          }
        }
        dateNode = node.path("dateCreated");
        if (!dateNode.isMissingNode() && !dateNode.asText().isEmpty()) {
          Instant parsed = parseInstantOrDate(dateNode.asText());
          if (parsed != null) {
            return parsed;
          }
        }
        // Fix 1: check @graph arrays (Sanity, WordPress, etc.)
        JsonNode graphNode = node.path("@graph");
        if (graphNode.isArray()) {
          for (JsonNode item : graphNode) {
            JsonNode graphDateNode = item.path("datePublished");
            if (!graphDateNode.isMissingNode() && !graphDateNode.asText().isEmpty()) {
              Instant parsed = parseInstantOrDate(graphDateNode.asText());
              if (parsed != null) {
                return parsed;
              }
            }
            graphDateNode = item.path("dateCreated");
            if (!graphDateNode.isMissingNode() && !graphDateNode.asText().isEmpty()) {
              Instant parsed = parseInstantOrDate(graphDateNode.asText());
              if (parsed != null) {
                return parsed;
              }
            }
          }
        }
      } catch (Exception ignored) {
        // JSON-LD parsing can fail for malformed data
      }
    }
    return null;
  }

  // Fix 5: extract text from article header area for date regex fallback
  private String extractHeaderText(Document doc) {
    Element header = doc.selectFirst(
        "article header, [class*=article-header], [class*=post-header], [class*=entry-header]");
    if (header != null) {
      return header.text();
    }
    Element article = doc.selectFirst("article, main, [class*=content], [class*=article]");
    if (article != null) {
      String text = article.text();
      return text.length() > 500 ? text.substring(0, 500) : text;
    }
    return null;
  }

  // Fix 5: extract a date Instant from free text using TEXT_DATE_PATTERN
  private Instant extractDateFromText(String text) {
    Matcher matcher = TEXT_DATE_PATTERN.matcher(text);
    if (matcher.find()) {
      try {
        String matched = matcher.group();
        for (String pattern : List.of(
            "d MMMM yyyy", "d MMM yyyy",
            "MMMM d, yyyy", "MMMM d yyyy",
            "MMM d, yyyy", "MMM d yyyy")) {
          try {
            return LocalDate.parse(matched.replaceAll(",", "").trim(),
                DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
          } catch (DateTimeParseException ignored) {
            // try next pattern
          }
        }
      } catch (Exception ignored) {
        // no parseable date found
      }
    }
    return null;
  }

  private Instant parseInstantOrDate(String value) {
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return parseDateOnly(value);
    }
  }

  private Instant parseDateTime(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return parseDateOnly(value);
    }
  }

  private Instant parseDateOnly(String value) {
    if (value == null || value.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(value.substring(0, 10))
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant();
    } catch (Exception ignored) {
      return null;
    }
  }

  private Instant parseDayMonthYear(String value) {
    try {
      return LocalDate.parse(value, DAY_MONTH_FORMATTER)
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private String extractArticleContent(Document doc) {
    Element article = doc.selectFirst("article");
    if (article != null) {
      return article.text();
    }
    Element main = doc.selectFirst("main");
    if (main != null) {
      return main.text();
    }
    Element content = doc.selectFirst("[class*=content], [class*=article], [class*=post]");
    if (content != null) {
      return content.text();
    }
    return doc.body() != null ? doc.body().text() : "";
  }

  private boolean looksLikeArticle(String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String path = uri.getPath();
      if (path == null) {
        return false;
      }

      // Skip localized URLs like /de/blog/, /ja/blog/
      if (path.matches("(?i)^/(de|fr|it|ja|ko|es|zh|ru|pt|nl)/(blog|news|article|post).*")) {
        return false;
      }

      String[] segments = path.split("/");
      java.util.List<String> validSegments = java.util.Arrays.stream(segments)
          .filter(s -> !s.isEmpty())
          .toList();

      if (validSegments.size() < 2) {
        return false;
      }

      String lastSegment = validSegments.get(validSegments.size() - 1);
      if (lastSegment.equals("category") || lastSegment.equals("tag")
          || lastSegment.equals("author") || lastSegment.equals("page")
          || lastSegment.equals("blog") || lastSegment.equals("news")
          || lastSegment.equals("developers") || lastSegment.equals("article")
          || lastSegment.equals("post") || lastSegment.equals("newsletter")) {
        return false;
      }

      return validSegments.contains("blog") || validSegments.contains("news")
          || validSegments.contains("article") || validSegments.contains("post");
    } catch (Exception e) {
      return false;
    }
  }
}
