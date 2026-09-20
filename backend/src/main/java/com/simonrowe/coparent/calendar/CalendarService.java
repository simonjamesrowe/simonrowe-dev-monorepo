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
    access.requireMember(familyId);
    validateEvent(familyId, values);
    final Instant now = Instant.now();
    final CalendarEvent saved = events.save(new CalendarEvent(null, familyId, values.type(),
        values.title().trim(), values.startDate(), values.endDate(), values.startTime(),
        values.endTime(), values.allDay(), values.parentId(), values.parentIds(),
        values.childIds(), values.location(), values.notes(), values.recurring(), null, now, now));
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
    final CalendarEvent current = getEvent(familyId, eventId);
    validateEvent(familyId, values);
    final CalendarEvent saved = events.save(new CalendarEvent(current.id(), familyId, values.type(),
        values.title().trim(), values.startDate(), values.endDate(), values.startTime(),
        values.endTime(), values.allDay(), values.parentId(), values.parentIds(),
        values.childIds(), values.location(), values.notes(), values.recurring(), null,
        current.createdAt(), Instant.now()));
    audits.record(familyId, "event", saved.id(), "update",
        Map.of("type", saved.type(), "title", saved.title(), "startDate", saved.startDate()));
    return saved;
  }

  /** Soft-deletes an authorised event. */
  public void deleteEvent(final ObjectId familyId, final ObjectId eventId) {
    final CalendarEvent current = getEvent(familyId, eventId);
    final Instant now = Instant.now();
    events.save(new CalendarEvent(current.id(), familyId, current.type(), current.title(),
        current.startDate(), current.endDate(), current.startTime(), current.endTime(),
        current.allDay(), current.parentId(), current.parentIds(), current.childIds(),
        current.location(), current.notes(), current.recurring(), now, current.createdAt(), now));
    audits.record(familyId, "event", eventId, "delete", Map.of("deletedAt", now));
  }

  /** Creates a family-defined event category. */
  public EventCategory createCategory(
      final ObjectId familyId,
      final CategoryValues values) {
    access.requireMember(familyId);
    validateCategory(values);
    final Instant now = Instant.now();
    final EventCategory saved = categories.save(new EventCategory(null, familyId,
        values.name().trim(), values.icon().trim(), values.color(), values.defaultCategory(),
        false, null, now, now));
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
    final EventCategory current = getCategory(familyId, categoryId);
    if (current.system()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "System categories cannot be modified");
    }
    validateCategory(values);
    final EventCategory saved = categories.save(new EventCategory(current.id(), familyId,
        values.name().trim(), values.icon().trim(), values.color(), values.defaultCategory(),
        false, null, current.createdAt(), Instant.now()));
    audits.record(familyId, "event_category", saved.id(), "update",
        Map.of("name", saved.name(), "icon", saved.icon()));
    return saved;
  }

  /** Soft-deletes a non-system event category. */
  public void deleteCategory(final ObjectId familyId, final ObjectId categoryId) {
    final EventCategory current = getCategory(familyId, categoryId);
    if (current.system()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "System categories cannot be deleted");
    }
    final Instant now = Instant.now();
    categories.save(new EventCategory(current.id(), familyId, current.name(), current.icon(),
        current.color(), current.defaultCategory(), false, now, current.createdAt(), now));
    audits.record(familyId, "event_category", categoryId, "delete", Map.of("deletedAt", now));
  }

  /** Creates a pending schedule-change request attributed to the caller's parent profile. */
  public ScheduleChangeRequest createChange(
      final ObjectId familyId,
      final ObjectId originalEventId,
      final ScheduleChangeRequest.ProposedChange proposedChange,
      final String reason) {
    final Parent actor = access.requireMember(familyId);
    if (reason == null || reason.isBlank() || proposedChange == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A proposed change and reason are required");
    }
    validateProposedChange(proposedChange);
    if (originalEventId != null) {
      getEvent(familyId, originalEventId);
    }
    final Instant now = Instant.now();
    final ScheduleChangeRequest saved = changes.save(new ScheduleChangeRequest(null, familyId,
        "pending", actor.id(), now, null, null, originalEventId, proposedChange, reason.trim(),
        null, null, now, now));
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

  /** Lets only the original requester withdraw a still-pending request. */
  public void withdrawChange(final ObjectId familyId, final ObjectId requestId) {
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
        current.responseNote(), now, current.createdAt(), now));
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
        && !List.of("daily", "weekly").contains(values.recurring().frequency())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recurrence frequency");
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
