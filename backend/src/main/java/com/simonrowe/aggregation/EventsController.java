package com.simonrowe.aggregation;

import com.simonrowe.shortlink.ShortLinkContentType;
import com.simonrowe.shortlink.ShortLinkService;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventsController {

  private final AggregatedEventRepository eventRepository;
  private final ShortLinkService shortLinkService;

  public EventsController(
      final AggregatedEventRepository eventRepository,
      final ShortLinkService shortLinkService
  ) {
    this.eventRepository = eventRepository;
    this.shortLinkService = shortLinkService;
  }

  @GetMapping
  public Page<EventResponse> listEvents(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final Boolean upcoming
  ) {
    var pageable = PageRequest.of(page, size);
    var now = Instant.now();

    if (Boolean.FALSE.equals(upcoming)) {
      return withShortUrls(eventRepository
          .findByVisibleTrueAndEventDateBeforeOrderByEventDateDesc(now, pageable));
    }

    // Default and explicit upcoming=true are the same query: upcoming, ascending.
    return withShortUrls(eventRepository
        .findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(now, pageable));
  }

  /**
   * Resolves every share URL on the page in one query rather than one per event.
   */
  private Page<EventResponse> withShortUrls(final Page<AggregatedEvent> events) {
    Map<String, String> shortUrls = shortLinkService.urlsFor(
        ShortLinkContentType.EVENT,
        events.getContent().stream().map(AggregatedEvent::id).toList());
    return events.map(event -> EventResponse.from(event, shortUrls.get(event.id())));
  }

  @GetMapping("/{id}")
  public ResponseEntity<EventResponse> getEventById(@PathVariable final String id) {
    return eventRepository.findById(id)
        .map(event -> EventResponse.from(
            event,
            shortLinkService.urlFor(ShortLinkContentType.EVENT, event.id()).orElse(null)))
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
