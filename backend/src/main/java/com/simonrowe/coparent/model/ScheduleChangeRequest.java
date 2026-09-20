package com.simonrowe.coparent.model;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A parent's auditable request to change a family schedule. */
@Document("schedulechangerequests")
public record ScheduleChangeRequest(
    @Id ObjectId id,
    ObjectId familyId,
    String status,
    ObjectId requestedBy,
    Instant requestedAt,
    ObjectId resolvedBy,
    Instant resolvedAt,
    ObjectId originalEventId,
    ProposedChange proposedChange,
    String reason,
    String responseNote,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public record ProposedChange(
      String type,
      String originalStartDate,
      String originalEndDate,
      String newStartDate,
      String newEndDate
  ) {
  }
}
