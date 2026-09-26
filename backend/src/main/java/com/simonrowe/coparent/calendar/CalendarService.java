package com.simonrowe.coparent.calendar;

import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.EventCategory;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.persistence.EventCategoryRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.persistence.ScheduleChangeRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Family-scoped calendar, category and schedule-change use cases. */
@Service
public class CalendarService {

  private static final String ACTIVE = CoparentAccessPolicy.ACTIVE;

  /** Roughly two years of weekly cancellations; bounds a document that grows by user action. */
  static final int MAX_EXCLUDED_DATES = 104;

  private final EventRepository events;
  private final EventCategoryRepository categories;
  private final ScheduleChangeRepository changes;
  private final ParentRepository parents;
  private final ChildRepository children;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final MongoTemplate mongoTemplate;

  /** Creates the calendar service with family-scoped persistence collaborators. */
  public CalendarService(
      final EventRepository events,
      final EventCategoryRepository categories,
      final ScheduleChangeRepository changes,
      final ParentRepository parents,
      final ChildRepository children,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this.events = events;
    this.categories = categories;
    this.changes = changes;
    this.parents = parents;
    this.children = children;
    this.access = access;
    this.audits = audits;
    this.mongoTemplate = mongoTemplate;
  }

  /** Creates an event after all referenced family rows have been checked in bounded queries. */
  public CalendarEvent createEvent(final ObjectId familyId, final EventValues values) {
    return createEvent(familyId, values, null, null);
  }

  /** Creates an event with preallocated assistant identifiers for retry reconciliation. */
  public CalendarEvent createEvent(
      final ObjectId familyId,
      final EventValues values,
      final ObjectId eventId,
      final ObjectId assistantActionId) {
    access.requireMember(familyId);
    validateEvent(familyId, values);
    final Instant now = Instant.now();
    final CalendarEvent saved = events.save(new CalendarEvent(eventId, familyId, values.type(),
        values.title().trim(), values.startDate(), values.endDate(), values.startTime(),
        values.endTime(), values.allDay(), values.parentId(), values.parentIds(),
        values.childIds(), values.location(), values.notes(), values.recurring(), assistantActionId,
        null, now, now));
    audits.record(familyId, "event", saved.id(), "create",
        Map.of("type", saved.type(), "title", saved.title(), "startDate", saved.startDate()));
    return saved;
  }

  /** Lists active events ordered by start instant. */
  public List<CalendarEvent> listEvents(final ObjectId familyId) {
    access.requireMember(familyId);
    return events.findByFamilyIdAndDeletedAtIsNullOrderByStartDateAsc(familyId);
  }

  /** Returns one event through its family-scoped key. */
  public CalendarEvent getEvent(final ObjectId familyId, final ObjectId eventId) {
    access.requireMember(familyId);
    return events.findByIdAndFamilyIdAndDeletedAtIsNull(eventId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
  }

  /** Replaces supported event values while retaining identity and lifecycle timestamps. */
  public CalendarEvent updateEvent(
      final ObjectId familyId,
      final ObjectId eventId,
      final EventValues values) {
    return updateEvent(familyId, eventId, values, null);
  }

  /** Replaces event values and records an internal assistant action marker. */
  public CalendarEvent updateEvent(
      final ObjectId familyId,
      final ObjectId eventId,
      final EventValues values,
      final ObjectId assistantActionId) {
    final CalendarEvent current = getEvent(familyId, eventId);
    validateEvent(familyId, values);
    final CalendarEvent saved = events.save(new CalendarEvent(current.id(), familyId, values.type(),
        values.title().trim(), values.startDate(), values.endDate(), values.startTime(),
        values.endTime(), values.allDay(), values.parentId(), values.parentIds(),
        values.childIds(), values.location(), values.notes(), values.recurring(),
        assistantActionId == null ? current.assistantActionId() : assistantActionId,
        null, current.createdAt(), Instant.now()));
    audits.record(familyId, "event", saved.id(), "update",
        Map.of("type", saved.type(), "title", saved.title(), "startDate", saved.startDate()));
    return saved;
  }

  /** Soft-deletes an authorised event. */
  public void deleteEvent(final ObjectId familyId, final ObjectId eventId) {
    deleteEvent(familyId, eventId, null);
  }

  /** Soft-deletes an event and records an internal assistant action marker. */
  public void deleteEvent(
      final ObjectId familyId, final ObjectId eventId, final ObjectId assistantActionId) {
    final CalendarEvent current = getEvent(familyId, eventId);
    final Instant now = Instant.now();
    events.save(new CalendarEvent(current.id(), familyId, current.type(), current.title(),
        current.startDate(), current.endDate(), current.startTime(), current.endTime(),
        current.allDay(), current.parentId(), current.parentIds(), current.childIds(),
        current.location(), current.notes(), current.recurring(),
        assistantActionId == null ? current.assistantActionId() : assistantActionId,
        now, current.createdAt(), now));
    audits.record(familyId, "event", eventId, "delete", Map.of("deletedAt", now));
  }

  /**
   * Skips one occurrence of a repeating event. Written as a single atomic update of the
   * skipped-dates set rather than a whole-event replacement, so skipping a week can never
   * overwrite a concurrent edit or drop a field the caller did not send.
   */
  public CalendarEvent skipOccurrence(
      final ObjectId familyId, final ObjectId eventId, final String date) {
    return changeOccurrence(familyId, eventId, date, true);
  }

  /** Restores a previously skipped occurrence of a repeating event. */
  public CalendarEvent restoreOccurrence(
      final ObjectId familyId, final ObjectId eventId, final String date) {
    return changeOccurrence(familyId, eventId, date, false);
  }

  private CalendarEvent changeOccurrence(
      final ObjectId familyId, final ObjectId eventId, final String date, final boolean skip) {
    access.requireMember(familyId);
    final String day = parseOccurrenceDate(date).toString();
    final Criteria target = Criteria.where("_id").is(eventId).and("familyId").is(familyId)
        .and("deletedAt").is(null).and("recurring").ne(null);
    if (skip) {
      // The cap is enforced in the same atomic write; re-skipping a date is always a no-op.
      target.orOperator(Criteria.where("recurring.excludedDates").is(day),
          Criteria.where("recurring.excludedDates." + (MAX_EXCLUDED_DATES - 1)).exists(false));
    }
    final Update update = skip
        ? new Update().addToSet("recurring.excludedDates", day)
        : new Update().pull("recurring.excludedDates", day);
    final CalendarEvent saved = mongoTemplate.findAndModify(Query.query(target),
        update.set("updatedAt", Instant.now()),
        FindAndModifyOptions.options().returnNew(true), CalendarEvent.class);
    if (saved == null) {
      final CalendarEvent current = getEvent(familyId, eventId);
      if (current.recurring() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Only a repeating event has occurrences to skip");
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A series can skip at most " + MAX_EXCLUDED_DATES + " dates");
    }
    audits.record(familyId, "event", eventId, skip ? "skip_occurrence" : "restore_occurrence",
        Map.of("date", day));
    return saved;
  }

  /** Creates a family-defined event category. */
  public EventCategory createCategory(
      final ObjectId familyId,
      final CategoryValues values) {
    return createCategory(familyId, values, null, null);
  }

  /** Creates a category with preallocated assistant identifiers. */
  public EventCategory createCategory(
      final ObjectId familyId,
      final CategoryValues values,
      final ObjectId categoryId,
      final ObjectId assistantActionId) {
    access.requireMember(familyId);
    validateCategory(values);
    final Instant now = Instant.now();
    final EventCategory saved = categories.save(new EventCategory(categoryId, familyId,
        values.name().trim(), values.icon().trim(), values.color(), values.defaultCategory(),
        false, assistantActionId, null, now, now));
    audits.record(familyId, "event_category", saved.id(), "create",
        Map.of("name", saved.name(), "icon", saved.icon()));
    return saved;
  }

  /** Lists active event categories for an authorised family. */
  public List<EventCategory> listCategories(final ObjectId familyId) {
    access.requireMember(familyId);
    return categories.findByFamilyIdAndDeletedAtIsNullOrderByNameAsc(familyId);
  }

  /** Returns one active category through its family-scoped key. */
  public EventCategory getCategory(final ObjectId familyId, final ObjectId categoryId) {
    access.requireMember(familyId);
    return categories.findByIdAndFamilyIdAndDeletedAtIsNull(categoryId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Event category not found"));
  }

  /** Replaces a non-system event category. */
  public EventCategory updateCategory(
      final ObjectId familyId,
      final ObjectId categoryId,
      final CategoryValues values) {
    return updateCategory(familyId, categoryId, values, null);
  }

  /** Replaces category values and records an internal assistant action marker. */
  public EventCategory updateCategory(
      final ObjectId familyId,
      final ObjectId categoryId,
      final CategoryValues values,
      final ObjectId assistantActionId) {
    final EventCategory current = getCategory(familyId, categoryId);
    if (current.system()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "System categories cannot be modified");
    }
    validateCategory(values);
    final EventCategory saved = categories.save(new EventCategory(current.id(), familyId,
        values.name().trim(), values.icon().trim(), values.color(), values.defaultCategory(),
        false, assistantActionId == null ? current.assistantActionId() : assistantActionId,
        null, current.createdAt(), Instant.now()));
    audits.record(familyId, "event_category", saved.id(), "update",
        Map.of("name", saved.name(), "icon", saved.icon()));
    return saved;
  }

  /** Soft-deletes a non-system event category. */
  public void deleteCategory(final ObjectId familyId, final ObjectId categoryId) {
    deleteCategory(familyId, categoryId, null);
  }

  /** Soft-deletes a category and records an internal assistant action marker. */
  public void deleteCategory(
      final ObjectId familyId, final ObjectId categoryId, final ObjectId assistantActionId) {
    final EventCategory current = getCategory(familyId, categoryId);
    if (current.system()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "System categories cannot be deleted");
    }
    final Instant now = Instant.now();
    categories.save(new EventCategory(current.id(), familyId, current.name(), current.icon(),
        current.color(), current.defaultCategory(), false,
        assistantActionId == null ? current.assistantActionId() : assistantActionId,
        now, current.createdAt(), now));
    audits.record(familyId, "event_category", categoryId, "delete", Map.of("deletedAt", now));
  }

  /** Creates a pending schedule-change request attributed to the caller's parent profile. */
  public ScheduleChangeRequest createChange(
      final ObjectId familyId,
      final ObjectId originalEventId,
      final ScheduleChangeRequest.ProposedChange proposedChange,
      final String reason) {
    return createChange(familyId, originalEventId, proposedChange, reason, null, null);
  }

  /** Creates a schedule request with preallocated assistant identifiers. */
  public ScheduleChangeRequest createChange(
      final ObjectId familyId,
      final ObjectId originalEventId,
      final ScheduleChangeRequest.ProposedChange proposedChange,
      final String reason,
      final ObjectId requestId,
      final ObjectId assistantActionId) {
    final Parent actor = access.requireMember(familyId);
    if (reason == null || reason.isBlank() || proposedChange == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A proposed change and reason are required");
    }
    validateProposedChange(proposedChange);
    // Approval applies the change to the calendar, so a request must say which event (and, for
    // a repeating one, which occurrence) it changes; only "add" can stand on its own.
    if (originalEventId == null && !"add".equals(proposedChange.type())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Choose the event this request changes");
    }
    if (originalEventId != null) {
      final CalendarEvent original = getEvent(familyId, originalEventId);
      if (original.recurring() != null && !"add".equals(proposedChange.type())) {
        requireDate(proposedChange.originalStartDate(),
            "Choose which occurrence of the repeating event this request changes");
      }
    }
    final Instant now = Instant.now();
    final ScheduleChangeRequest saved = changes.save(new ScheduleChangeRequest(requestId, familyId,
        "pending", actor.id(), now, null, null, originalEventId, proposedChange, reason.trim(),
        null, assistantActionId, null, now, now));
    audits.record(familyId, "schedule_change_request", saved.id(), "create",
        Map.of("reason", saved.reason(), "changeType", proposedChange.type()));
    return saved;
  }

  /** Lists active schedule-change requests newest first. */
  public List<ScheduleChangeRequest> listChanges(final ObjectId familyId) {
    access.requireMember(familyId);
    return changes.findByFamilyIdAndDeletedAtIsNullOrderByRequestedAtDesc(familyId);
  }

  /** Returns one family-scoped active schedule-change request. */
  public ScheduleChangeRequest getChange(final ObjectId familyId, final ObjectId requestId) {
    access.requireMember(familyId);
    return changes.findByIdAndFamilyIdAndDeletedAtIsNull(requestId, familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Schedule change request not found"));
  }

  /** Atomically approves or declines a pending request as somebody other than its requester. */
  public ScheduleChangeRequest resolveChange(
      final ObjectId familyId,
      final ObjectId requestId,
      final String decision,
      final String responseNote) {
    if (!List.of("approved", "declined").contains(decision)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid decision");
    }
    final Parent actor = access.requireMember(familyId);
    final Instant now = Instant.now();
    final ScheduleChangeRequest saved = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(requestId).and("familyId").is(familyId)
            .and("deletedAt").is(null).and("status").is("pending")
            .and("requestedBy").ne(actor.id())),
        new Update().set("status", decision).set("resolvedBy", actor.id())
            .set("resolvedAt", now).set("responseNote", responseNote).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), ScheduleChangeRequest.class);
    if (saved == null) {
      final ScheduleChangeRequest current = getChange(familyId, requestId);
      if (actor.id().equals(current.requestedBy())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "You cannot resolve your own request");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Schedule change request is no longer pending");
    }
    audits.record(familyId, "schedule_change_request", requestId, decision, Map.of());
    return saved;
  }

  /**
   * Puts an approval that could not be applied back to pending. The claim and the calendar
   * writes are separate operations (the database runs without transactions), so this is the
   * compensation that keeps an "approved" status meaning "the calendar was changed". Matched on
   * the approver as well as the status, so it can never reopen somebody else's decision.
   */
  void reopenChange(final ObjectId familyId, final ObjectId requestId, final ObjectId approver) {
    mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(requestId).and("familyId").is(familyId)
            .and("status").is("approved").and("resolvedBy").is(approver)),
        new Update().set("status", "pending").unset("resolvedBy").unset("resolvedAt")
            .unset("responseNote").set("updatedAt", Instant.now()),
        ScheduleChangeRequest.class);
    audits.record(familyId, "schedule_change_request", requestId, "approval_reverted", Map.of());
  }

  /** Lets only the original requester withdraw a still-pending request. */
  public void withdrawChange(final ObjectId familyId, final ObjectId requestId) {
    withdrawChange(familyId, requestId, null);
  }

  /** Withdraws a schedule request and records an internal assistant action marker. */
  public void withdrawChange(
      final ObjectId familyId, final ObjectId requestId, final ObjectId assistantActionId) {
    final Parent actor = access.requireMember(familyId);
    final ScheduleChangeRequest current = getChange(familyId, requestId);
    if (!actor.id().equals(current.requestedBy())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Only the requester can withdraw this request");
    }
    if (!"pending".equals(current.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Resolved requests cannot be withdrawn");
    }
    final Instant now = Instant.now();
    changes.save(new ScheduleChangeRequest(current.id(), familyId, current.status(),
        current.requestedBy(), current.requestedAt(), current.resolvedBy(), current.resolvedAt(),
        current.originalEventId(), current.proposedChange(), current.reason(),
        current.responseNote(),
        assistantActionId == null ? current.assistantActionId() : assistantActionId,
        now, current.createdAt(), now));
    audits.record(familyId, "schedule_change_request", requestId, "withdraw", Map.of());
  }

  private void validateEvent(final ObjectId familyId, final EventValues values) {
    if (values.type() == null || values.type().isBlank()
        || values.title() == null || values.title().isBlank()
        || values.startDate() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Event type, title and start date are required");
    }
    if (values.endDate() != null && values.endDate().isBefore(values.startDate())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Event end cannot precede its start");
    }
    if (values.childIds() == null || values.childIds().isEmpty()
        || children.countByIdInAndFamilyIdAndDeletedAtIsNull(values.childIds(), familyId)
        != values.childIds().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "At least one valid family child is required");
    }
    if (values.parentId() != null
        && parents.findByIdAndFamilyIdAndStatus(values.parentId(), familyId, ACTIVE).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent identifier");
    }
    if (values.parentIds() != null && !values.parentIds().isEmpty()
        && parents.countByIdInAndFamilyIdAndStatus(values.parentIds(), familyId, ACTIVE)
        != values.parentIds().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "One or more parent identifiers are invalid");
    }
    if (values.recurring() != null
        && !Recurrence.FREQUENCIES.contains(values.recurring().frequency())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recurrence frequency");
    }
    if (values.recurring() != null
        && !Recurrence.DAYS.containsAll(values.recurring().days())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Recurring days must be lowercase weekday names such as monday");
    }
    if (values.recurring() != null) {
      if (values.recurring().excludedDates().size() > MAX_EXCLUDED_DATES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "A series can skip at most " + MAX_EXCLUDED_DATES + " dates");
      }
      values.recurring().excludedDates().forEach(CalendarService::parseOccurrenceDate);
    }
  }

  private static void requireDate(final String value, final String message) {
    try {
      LocalDate.parse(value == null ? "" : value);
    } catch (java.time.format.DateTimeParseException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, exception);
    }
  }

  private static LocalDate parseOccurrenceDate(final String value) {
    try {
      return LocalDate.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Skipped dates must use YYYY-MM-DD", exception);
    }
  }

  private static void validateCategory(final CategoryValues values) {
    if (values.name() == null || values.name().isBlank()
        || values.icon() == null || values.icon().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Category name and icon are required");
    }
  }

  private static void validateProposedChange(
      final ScheduleChangeRequest.ProposedChange proposed) {
    if (!List.of("swap", "extend", "add", "remove").contains(proposed.type())
        || proposed.newStartDate() == null || proposed.newEndDate() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid proposed change");
    }
    try {
      final LocalDate start = LocalDate.parse(proposed.newStartDate());
      final LocalDate end = LocalDate.parse(proposed.newEndDate());
      if (end.isBefore(start)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Proposed end cannot precede its start");
      }
    } catch (java.time.format.DateTimeParseException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Proposed dates must use YYYY-MM-DD", exception);
    }
  }

  /** Complete values accepted for event create and replacement. */
  public record EventValues(
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
      CalendarEvent.Recurring recurring
  ) {
    public EventValues {
      parentIds = parentIds == null ? List.of() : List.copyOf(parentIds);
      childIds = childIds == null ? List.of() : List.copyOf(childIds);
    }
  }

  /** Complete values accepted for category create and replacement. */
  public record CategoryValues(
      String name,
      String icon,
      String color,
      boolean defaultCategory
  ) {
  }
}
