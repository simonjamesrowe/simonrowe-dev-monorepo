package com.simonrowe.coparent.model;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One authenticated person's membership of a family. */
@Document("parents")
public record Parent(
    @Id ObjectId id,
    String auth0Id,
    ObjectId familyId,
    String fullName,
    String email,
    String role,
    String status,
    String color,
    String avatarUrl,
    Instant lastSignedInAt,
    Instant createdAt,
    Instant updatedAt
) {
}
