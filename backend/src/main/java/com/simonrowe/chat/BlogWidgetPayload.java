package com.simonrowe.chat;

import java.util.List;

public record BlogWidgetPayload(List<Post> posts) {

  public record Post(
      String id,
      String title,
      String summary,
      List<String> tags,
      String publishedDate,
      String url
  ) {
  }
}
