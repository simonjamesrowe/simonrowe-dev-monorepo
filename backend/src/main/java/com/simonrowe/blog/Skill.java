package com.simonrowe.blog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "skills")
public record Skill(
    @Id String id,
    String name
) {
}
