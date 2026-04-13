package com.simonrowe.aggregation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "aggregated_events")
@CompoundIndex(
    name = "idx_visible_event_date",
    def = "{'visible': 1, 'eventDate': -1}")
public record AggregatedEvent(
    @Id String id,
    String title,
    String sourceName,
    @Indexed(unique = true) String originalUrl,
    String summary,
    String description,
    @Indexed Instant eventDate,
    Instant eventEndDate,
    String venue,
    String location,
    Instant fetchedAt,
    boolean visible
) {
}
