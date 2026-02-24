package com.simonrowe.tour;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "tourSteps")
public record TourStep(
    @Id String id,
    @Indexed(unique = true) int order,
    String targetSelector,
    String title,
    String titleImage,
    String description,
    String position
) {
}
