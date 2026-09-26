package com.simonrowe.coparent.model;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Family-defined calendar presentation category. */
@Document("eventcategories")
public record EventCategory(
    @Id ObjectId id,
    ObjectId familyId,
    String name,
    String icon,
    String color,
    @Field("isDefault") boolean defaultCategory,
    @Field("isSystem") boolean system,
    ObjectId assistantActionId,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
