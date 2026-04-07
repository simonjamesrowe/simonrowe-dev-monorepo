package com.simonrowe.admin;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "code_examples")
public record CodeExample(
    @Id String id,
    @Indexed(unique = true) String title,
    String description,
    @Indexed String language,
    String code,
    @DBRef List<Skill> skills,
    Instant createdAt,
    Instant updatedAt
) {
}
