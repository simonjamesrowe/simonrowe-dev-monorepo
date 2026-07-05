package com.simonrowe.chat;

import java.util.List;

public record EventWidgetPayload(List<Event> events) {

  public record Event(
      String id,
      String title,
      String summary,
      String sourceName,
      String originalUrl,
      String eventDate,
      String eventEndDate,
      String venue,
      String location,
      String imageUrl
  ) {
  }
}
