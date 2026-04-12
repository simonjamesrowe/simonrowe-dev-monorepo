package com.simonrowe.aggregation;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AggregatedEventRepository
    extends MongoRepository<AggregatedEvent, String> {

  Page<AggregatedEvent> findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
      Instant now, Pageable pageable);

  List<AggregatedEvent> findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
      Instant now);

  Page<AggregatedEvent> findByVisibleTrueAndEventDateBeforeOrderByEventDateDesc(
      Instant now, Pageable pageable);

  List<AggregatedEvent> findByVisibleTrueOrderByEventDateDesc();

  boolean existsByOriginalUrl(String originalUrl);
}
