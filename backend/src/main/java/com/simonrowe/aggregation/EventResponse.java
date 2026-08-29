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
    boolean visible,
    String shortUrl
) {

  /**
   * Builds the response with no share URL, for the admin and favourites paths that render
   * no Share control.
   *
   * @param event the event
   * @return the response, with a null {@code shortUrl}
   */
  public static EventResponse from(final AggregatedEvent event) {
    return from(event, null);
  }

  /**
   * Builds the response with a resolved share URL.
   *
   * <p>Absolute, so the frontend never concatenates a base; nullable, so an event with no
   * link yet renders without a Share control rather than a broken URL.
   *
   * @param event the event
   * @param shortUrl the absolute share URL, or null
   * @return the response
   */
  public static EventResponse from(final AggregatedEvent event, final String shortUrl) {
    return new EventResponse(
        event.id(), event.title(), event.sourceName(),
        event.originalUrl(), event.summary(), event.description(),
        event.eventDate(), event.eventEndDate(), event.venue(),
        event.location(), event.fetchedAt(), event.visible(), shortUrl);
  }
}
