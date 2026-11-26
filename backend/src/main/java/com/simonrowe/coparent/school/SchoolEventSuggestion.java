package com.simonrowe.coparent.school;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A school event offered for a family's calendar, and what the family decided about it.
 *
 * <p>Also the ledger: every school event row that has ever been suggested to a family is in one
 * of these, whatever was decided, and a unique index on {@code (familyId, sourceEventIds)} keeps
 * it in only one. That is what stops a dismissed event coming back on the next sync.
 *
 * @param id the suggestion's id
 * @param familyId the family it is for
 * @param sourceEventIds the Term Time event rows it stands for: one event the school described in
 *     several places is several rows
 * @param sourceTitles their titles as the school wrote them, so a parent can see where it came from
 * @param childIds the children it is for
 * @param details what goes on the calendar
 * @param sourceFingerprint the source rows' dates, times and places when last read, so a change
 *     the school makes after a family accepted can be asked about again
 * @param status where it is
 * @param review for an accepted event: what changed at the school since, or null when nothing
 * @param proposed for a {@link Review#CHANGED} event, the details as the school now gives them
 * @param calendarEventId the calendar event it became, once accepted
 * @param decidedBy the parent who accepted or dismissed it
 * @param decidedAt when
 * @param createdAt when it was first suggested
 * @param updatedAt when it last changed
 */
@Document(SchoolEventSuggestion.COLLECTION)
public record SchoolEventSuggestion(
    @Id ObjectId id,
    ObjectId familyId,
    List<String> sourceEventIds,
    List<String> sourceTitles,
    List<ObjectId> childIds,
    Details details,
    String sourceFingerprint,
    Status status,
    Review review,
    Details proposed,
    ObjectId calendarEventId,
    ObjectId decidedBy,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt
) {

  public static final String COLLECTION = "school_event_suggestions";

  public SchoolEventSuggestion {
    sourceEventIds = sourceEventIds == null ? List.of() : List.copyOf(sourceEventIds);
    sourceTitles = sourceTitles == null ? List.of() : List.copyOf(sourceTitles);
    childIds = childIds == null ? List.of() : List.copyOf(childIds);
  }

  /** Where a suggestion is. */
  public enum Status {
    /** Waiting for a parent. */
    PENDING,
    /** On the calendar. */
    ACCEPTED,
    /** A parent said no; never offered again. */
    DISMISSED,
    /** The school no longer states it, before anyone decided, or a parent took it off. */
    WITHDRAWN
  }

  /** What the school changed about an event after the family accepted it. */
  public enum Review {
    /** Its date, time or place moved. */
    CHANGED,
    /** The school no longer states it at all. */
    REMOVED
  }

  /**
   * What a calendar event made from the suggestion holds.
   *
   * @param title a short title
   * @param startDate first day
   * @param endDate last day, the same as the first for one day
   * @param startTime {@code HH:mm}, or null for all day
   * @param endTime {@code HH:mm}, or null
   * @param location where, when the school says
   * @param notes what the school said about it, and where
   * @param sourceTime the school's own wording of the time, kept for when it could not be read
   */
  public record Details(
      String title,
      LocalDate startDate,
      LocalDate endDate,
      String startTime,
      String endTime,
      String location,
      String notes,
      String sourceTime
  ) {

    /** Whether the event has no time of day. */
    public boolean allDay() {
      return startTime == null;
    }

    /** The same details under another title. */
    Details withTitle(final String newTitle) {
      return new Details(newTitle, startDate, endDate, startTime, endTime, location, notes,
          sourceTime);
    }

    /** Whether a parent would see a difference on the calendar. */
    boolean sameOnCalendar(final Details other) {
      return other != null && Objects.equals(startDate, other.startDate)
          && Objects.equals(endDate, other.endDate)
          && Objects.equals(startTime, other.startTime)
          && Objects.equals(endTime, other.endTime)
          && Objects.equals(location, other.location);
    }
  }
}
