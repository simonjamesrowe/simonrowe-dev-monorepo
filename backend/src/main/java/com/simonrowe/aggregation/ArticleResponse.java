package com.simonrowe.aggregation;

import java.time.Instant;

public record ArticleResponse(
    String id,
    String title,
    String sourceName,
    String originalUrl,
    String summary,
    String author,
    Instant publishedDate,
    Instant fetchedAt,
    boolean visible,
    String imageUrl
) {

  public static ArticleResponse from(final AggregatedArticle article) {
    return new ArticleResponse(
        article.id(),
        article.title(),
        article.sourceName(),
        article.originalUrl(),
        article.summary(),
        article.author(),
        article.publishedDate(),
        article.fetchedAt(),
        article.visible(),
        article.imageUrl());
  }
}
