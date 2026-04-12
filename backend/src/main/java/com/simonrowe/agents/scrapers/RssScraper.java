package com.simonrowe.agents.scrapers;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RssScraper {

  private static final Logger log = LoggerFactory.getLogger(RssScraper.class);

  public List<ScrapedContent> scrape(String feedUrl) {
    return scrape(feedUrl, false);
  }

  public List<ScrapedContent> scrape(String feedUrl, boolean isEvent) {
    List<ScrapedContent> results = new ArrayList<>();
    try {
      SyndFeedInput input = new SyndFeedInput();
      SyndFeed feed = input.build(new XmlReader(URI.create(feedUrl).toURL()));
      for (SyndEntry entry : feed.getEntries()) {
        String title = entry.getTitle();
        String link = entry.getLink();
        String content = entry.getDescription() != null
            ? entry.getDescription().getValue() : "";
        Instant published = entry.getPublishedDate() != null
            ? entry.getPublishedDate().toInstant() : null;
        String author = entry.getAuthor();
        results.add(new ScrapedContent(title, link, content, published, author, null, isEvent));
      }
      log.info("Scraped {} items from RSS feed: {}", results.size(), feedUrl);
    } catch (Exception e) {
      log.error("Failed to scrape RSS feed: {}", feedUrl, e);
    }
    return results;
  }
}
