package com.simonrowe.agents.scrapers;

import java.time.Instant;

public record ScrapedContent(
    String title,
    String url,
    String content,
    Instant publishedDate,
    String author,
    String imageUrl,
    boolean isEvent,
    String venue,
    String location
) {

  public ScrapedContent(String title, String url, String content,
      Instant publishedDate, String author, String imageUrl, boolean isEvent) {
    this(title, url, content, publishedDate, author, imageUrl, isEvent, null, null);
  }
}
