package com.simonrowe.search.elasticsearch;

import java.time.Instant;

public record SiteSearchDocument(
    String id,
    String name,
    String type,
    String shortDescription,
    String longDescription,
    String company,
    String image,
    String url,
    Instant sortDate
) {
}
