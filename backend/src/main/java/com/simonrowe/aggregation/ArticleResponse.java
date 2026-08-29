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
    String imageUrl,
    String shortUrl
) {

  /**
   * Builds the response with no share URL.
   *
   * <p>Kept as the one-argument form because the admin and favourites paths have no reason
   * to resolve a share link — they render no Share control. Only the public listing and
   * detail paths pass one, which is part of why {@code shortUrl} is legitimately nullable.
   *
   * @param article the article
   * @return the response, with a null {@code shortUrl}
   */
  public static ArticleResponse from(final AggregatedArticle article) {
    return from(article, null);
  }

  /**
   * Builds the response with a resolved share URL.
   *
   * <p>{@code shortUrl} is the full absolute address, so the frontend never concatenates a
   * base. Null means the article has no link yet, and the Share control is simply absent.
   *
   * @param article the article
   * @param shortUrl the absolute share URL, or null
   * @return the response
   */
  public static ArticleResponse from(final AggregatedArticle article, final String shortUrl) {
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
        article.imageUrl(),
        shortUrl);
  }
}
