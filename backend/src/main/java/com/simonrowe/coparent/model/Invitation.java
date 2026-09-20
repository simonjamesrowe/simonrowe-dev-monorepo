package com.simonrowe.coparent.model;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A time-limited, single-use invitation to a family. */
@Document("invitations")
public record Invitation(
    @Id ObjectId id,
    ObjectId familyId,
    String email,
    String role,
    String status,
    String token,
    Instant sentAt,
    Instant expiresAt,
    Instant acceptedAt,
    Instant canceledAt,
    String acceptedByAuth0Id,
    ObjectId acceptedParentId,
    Instant createdAt,
    Instant updatedAt
) {
}
