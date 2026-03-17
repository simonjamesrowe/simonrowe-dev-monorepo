package com.simonrowe.admin;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "skill_groups")
public record SkillGroup(
    @Id String id,
    String name,
    Double rating,
    String description,
    Image image,
    @Field("displayOrder") @Indexed Integer order,
    List<String> skills,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
