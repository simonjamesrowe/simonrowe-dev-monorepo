package com.simonrowe.search.elasticsearch;

import java.time.Instant;
import java.util.List;

public record BlogSearchDocument(
    String id,
    String title,
    String shortDescription,
    String content,
    List<String> tags,
    List<String> skills,
    String image,
    Instant publishedDate,
    String url
) {
}
