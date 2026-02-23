package com.simonrowe.search;

import java.time.Instant;

public record BlogSearchResult(
    String title,
    String shortDescription,
    String image,
    Instant publishedDate,
    String url
) {
}
