package com.simonrowe.agents.scrapers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  public List<ScrapedContent> scrape(String sitemapUrl) {
    List<ScrapedContent> results = new ArrayList<>();
    try {
      Document sitemap = Jsoup.connect(sitemapUrl)
          .timeout(TIMEOUT_MS)
          .userAgent("SimonRoweBot/1.0")
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
          if (content != null) {
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

  public List<ScrapedContent> scrapeEventsPage(String pageUrl) {
    List<ScrapedContent> results = new ArrayList<>();
    try {
      Document doc = Jsoup.connect(pageUrl)
          .timeout(TIMEOUT_MS)
          .userAgent("SimonRoweBot/1.0")
          .get();

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

        results.add(new ScrapedContent(title, link, description, eventDate, null, imageUrl, true));
      }
      log.info("Scraped {} events from page: {}", results.size(), pageUrl);
    } catch (Exception e) {
      log.error("Failed to scrape events page: {}", pageUrl, e);
    }
    return results;
  }

  private ScrapedContent scrapeArticlePage(String url) throws Exception {
    Document doc = Jsoup.connect(url)
        .timeout(TIMEOUT_MS)
        .userAgent("SimonRoweBot/1.0")
        .get();

    String title = doc.title();
    Element ogTitle = doc.selectFirst("meta[property=og:title]");
    if (ogTitle != null && !ogTitle.attr("content").isEmpty()) {
      title = ogTitle.attr("content");
    }

    String content = extractArticleContent(doc);
    if (title.isEmpty() || content.isEmpty()) {
      return null;
    }

    String author = null;
    Element authorMeta = doc.selectFirst("meta[name=author]");
    if (authorMeta != null) {
      author = authorMeta.attr("content");
    }

    String imageUrl = null;
    Element ogImage = doc.selectFirst("meta[property=og:image]");
    if (ogImage != null) {
      imageUrl = ogImage.attr("content");
    }

    return new ScrapedContent(title, url, content, extractPublishedDate(doc), author, imageUrl,
        false);
  }

  private Instant extractPublishedDate(Document doc) {
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

    return Instant.now();
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
      return java.time.LocalDate.parse(value.substring(0, 10))
          .atStartOfDay(java.time.ZoneOffset.UTC)
          .toInstant();
    } catch (Exception ignored) {
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
    return url.contains("/article") || url.contains("/blog")
        || url.contains("/post") || url.contains("/news")
        || url.matches(".*\\/\\d{4}\\/.*");
  }
}
