package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "tour_steps")
public record TourStep(
    @Id String id,
    String title,
    String selector,
    String description,
    String titleImage,
    String position,
    @Indexed int order,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
