package com.simonrowe.search.elasticsearch;

public record SiteSearchDocument(
    String id,
    String name,
    String type,
    String shortDescription,
    String longDescription,
    String image,
    String url
) {
}
