package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A CoParent family tenant. */
@Document("families")
public record Family(
    @Id ObjectId id,
    String name,
    String timeZone,
    List<ObjectId> parentIds,
    List<ObjectId> childIds,
    List<ObjectId> invitationIds,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public Family {
    parentIds = parentIds == null ? List.of() : List.copyOf(parentIds);
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
    invitationIds = invitationIds == null ? List.of() : List.copyOf(invitationIds);
  }
}
