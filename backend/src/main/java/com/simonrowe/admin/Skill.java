package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "skills")
public record Skill(
    @Id String id,
    @Indexed(unique = true) String name,
    Double rating,
    String description,
    String image,
    @Indexed int order,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
