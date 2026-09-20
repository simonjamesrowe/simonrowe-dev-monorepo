package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Family calendar event, including optional recurrence metadata. */
@Document("events")
public record CalendarEvent(
    @Id ObjectId id,
    ObjectId familyId,
    String type,
    String title,
    Instant startDate,
    Instant endDate,
    String startTime,
    String endTime,
    boolean allDay,
    ObjectId parentId,
    List<ObjectId> parentIds,
    List<ObjectId> childIds,
    String location,
    String notes,
    Recurring recurring,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public CalendarEvent {
    parentIds = parentIds == null ? List.of() : List.copyOf(parentIds);
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
  }

  public record Recurring(String frequency, List<String> days) {
    public Recurring {
      days = days == null ? List.of() : List.copyOf(days);
    }
  }
}
