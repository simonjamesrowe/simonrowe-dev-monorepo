package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only attribution for a CoParent mutation. */
@Document("audits")
public record AuditRecord(
    @Id ObjectId id,
    ObjectId familyId,
    String entityType,
    String entityId,
    String action,
    String performedBy,
    Map<String, Object> changes,
    Instant timestamp,
    Instant createdAt,
    Instant updatedAt
) {
  public AuditRecord {
    changes = changes == null ? Map.of() : Map.copyOf(changes);
  }
}
