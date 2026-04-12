package com.simonrowe.agents.scrapers;

import java.time.Instant;

public record ScrapedContent(
    String title,
    String url,
    String content,
    Instant publishedDate,
    String author,
    String imageUrl,
    boolean isEvent
) {
}
