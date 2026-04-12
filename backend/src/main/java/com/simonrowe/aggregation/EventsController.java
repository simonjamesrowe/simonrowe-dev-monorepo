package com.simonrowe.aggregation;

import java.time.Instant;
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

  public EventsController(final AggregatedEventRepository eventRepository) {
    this.eventRepository = eventRepository;
  }

  @GetMapping
  public Page<EventResponse> listEvents(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final Boolean upcoming
  ) {
    var pageable = PageRequest.of(page, size);
    var now = Instant.now();

    if (Boolean.TRUE.equals(upcoming)) {
      return eventRepository
          .findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(now, pageable)
          .map(EventResponse::from);
    }

    if (Boolean.FALSE.equals(upcoming)) {
      return eventRepository
          .findByVisibleTrueAndEventDateBeforeOrderByEventDateDesc(now, pageable)
          .map(EventResponse::from);
    }

    // Default: upcoming first (ascending), then past (descending) - return upcoming
    return eventRepository
        .findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(now, pageable)
        .map(EventResponse::from);
  }

  @GetMapping("/{id}")
  public ResponseEntity<EventResponse> getEventById(@PathVariable final String id) {
    return eventRepository.findById(id)
        .map(EventResponse::from)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
