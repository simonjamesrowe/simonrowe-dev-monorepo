package com.simonrowe.blog;

import java.time.Instant;

public record BlogSearchResult(
    String id,
    String title,
    String thumbnailImage,
    Instant createdDate
) {

  public static BlogSearchResult fromDocument(final BlogSearchDocument doc) {
    return new BlogSearchResult(
        doc.id(),
        doc.title(),
        doc.thumbnailImage(),
        doc.createdDate()
    );
  }
}
