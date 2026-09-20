package com.simonrowe.coparent.model;

import java.time.Instant;
import java.time.LocalDate;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Private child profile owned by one family. */
@Document("children")
public record Child(
    @Id ObjectId id,
    ObjectId familyId,
    String fullName,
    LocalDate dateOfBirth,
    String school,
    String medicalNotes,
    String avatarUrl,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
