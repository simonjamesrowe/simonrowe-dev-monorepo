package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "tourSteps")
public record TourStep(
    @Id String id,
    String title,
    @Field("targetSelector")
    String selector,
    String description,
    String titleImage,
    String position,
    @Indexed(unique = true) int order,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId,
    String route
) {
}
