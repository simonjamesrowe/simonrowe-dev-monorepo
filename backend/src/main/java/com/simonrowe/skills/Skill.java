package com.simonrowe.skills;

import com.simonrowe.common.Image;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "skills")
public record Skill(
    @Id String id,
    String name,
    Double rating,
    Integer displayOrder,
    String description,
    Image image
) {
}
