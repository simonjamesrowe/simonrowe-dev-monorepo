package com.simonrowe.aggregation;

import java.time.Instant;

public record EventResponse(
    String id,
    String title,
    String sourceName,
    String originalUrl,
    String summary,
    String description,
    Instant eventDate,
    Instant eventEndDate,
    String venue,
    String location,
    Instant fetchedAt,
    boolean visible
) {

  public static EventResponse from(AggregatedEvent event) {
    return new EventResponse(
        event.id(), event.title(), event.sourceName(),
        event.originalUrl(), event.summary(), event.description(),
        event.eventDate(), event.eventEndDate(), event.venue(),
        event.location(), event.fetchedAt(), event.visible());
  }
}
