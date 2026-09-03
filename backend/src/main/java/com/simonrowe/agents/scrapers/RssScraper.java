package com.simonrowe.agents.scrapers;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RssScraper {

  private static final Logger log = LoggerFactory.getLogger(RssScraper.class);

  public List<ScrapedContent> scrape(String feedUrl) {
    return scrape(feedUrl, false, null);
  }

  public List<ScrapedContent> scrape(String feedUrl, boolean isEvent) {
    return scrape(feedUrl, isEvent, null);
  }

  /**
   * Reads a feed, optionally keeping only entries carrying a given category.
   *
   * <p>The filter exists because some publishers offer no per-section feed. OpenAI's
   * {@code /news/rss.xml} is the whole site — over a thousand items across twenty-plus
   * categories — and its engineering section is reachable only as a {@code <category>}
   * label, every HTML page on the domain being bot-blocked. Without this the only way to
   * follow one section would be to ingest all of them.
   *
   * @param categoryFilter category name to require, matched case-insensitively against
   *     <em>any</em> of an entry's categories; {@code null} or blank keeps every entry.
   *     An entry carrying no categories at all never matches a non-blank filter.
   */
  public List<ScrapedContent> scrape(String feedUrl, boolean isEvent, String categoryFilter) {
    List<ScrapedContent> results = new ArrayList<>();
    int skipped = 0;
    try {
      SyndFeedInput input = new SyndFeedInput();
      SyndFeed feed = input.build(new XmlReader(URI.create(feedUrl).toURL()));
      for (SyndEntry entry : feed.getEntries()) {
        if (!matchesCategory(entry, categoryFilter)) {
          skipped++;
          continue;
        }
        String title = entry.getTitle();
        String link = entry.getLink();
        String content = extractBestContent(entry);
        Instant published = entry.getPublishedDate() != null
            ? entry.getPublishedDate().toInstant() : null;
        String author = entry.getAuthor();
        results.add(new ScrapedContent(title, link, content, published, author, null, isEvent));
      }
      if (skipped > 0) {
        log.info("Scraped {} items from RSS feed: {} ({} skipped, not in category '{}')",
            results.size(), feedUrl, skipped, categoryFilter);
      } else {
        log.info("Scraped {} items from RSS feed: {}", results.size(), feedUrl);
      }
    } catch (Exception e) {
      log.error("Failed to scrape RSS feed: {}", feedUrl, e);
    }
    return results;
  }

  private boolean matchesCategory(SyndEntry entry, String categoryFilter) {
    if (categoryFilter == null || categoryFilter.isBlank()) {
      return true;
    }
    List<SyndCategory> categories = entry.getCategories();
    if (categories == null || categories.isEmpty()) {
      return false;
    }
    String wanted = categoryFilter.trim();
    for (SyndCategory category : categories) {
      String name = category.getName();
      if (name != null && name.trim().equalsIgnoreCase(wanted)) {
        return true;
      }
    }
    return false;
  }

  private String extractBestContent(SyndEntry entry) {
    List<SyndContent> contents = entry.getContents();
    if (contents != null && !contents.isEmpty()) {
      for (SyndContent c : contents) {
        if (c.getValue() != null && !c.getValue().isBlank()) {
          return stripHtml(c.getValue());
        }
      }
    }

    if (entry.getDescription() != null
        && entry.getDescription().getValue() != null
        && !entry.getDescription().getValue().isBlank()) {
      return stripHtml(entry.getDescription().getValue());
    }

    return "";
  }

  private String stripHtml(String html) {
    if (html == null) {
      return "";
    }
    return Jsoup.parse(html).text();
  }
}
