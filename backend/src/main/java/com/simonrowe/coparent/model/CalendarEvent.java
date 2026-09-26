package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    ObjectId assistantActionId,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public CalendarEvent {
    parentIds = parentIds == null ? List.of() : List.copyOf(parentIds);
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
  }

  /**
   * How an event repeats. {@code excludedDates} are {@code YYYY-MM-DD} dates on which a single
   * occurrence is skipped (a cancelled week) without touching the rest of the series.
   */
  public record Recurring(String frequency, List<String> days, List<String> excludedDates) {
    public Recurring {
      days = days == null ? List.of() : List.copyOf(days);
      excludedDates = excludedDates == null ? List.of()
          : excludedDates.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    public Recurring(final String frequency, final List<String> days) {
      this(frequency, days, List.of());
    }
  }
}
