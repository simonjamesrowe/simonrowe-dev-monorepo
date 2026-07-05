package com.simonrowe.chat;

import java.util.List;

public record NewsWidgetPayload(List<Article> articles) {

  public record Article(
      String id,
      String title,
      String summary,
      String sourceName,
      String originalUrl,
      String publishedDate,
      String imageUrl
  ) {
  }
}
