package com.simonrowe.coparent.school;

import com.simonrowe.coparent.calendar.CalendarService;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.CoparentAuditService;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a parent does with school events offered to their family. One parent's decision is
 * enough: an event the school published is a fact about the children's day, not a change to who
 * has them, so nothing here waits for the other parent the way a schedule change does.
 *
 * <p>Each decision is claimed with a conditional update before the calendar is written, so two
 * parents pressing Add at once make one calendar event. The database runs without transactions,
 * so a calendar write that fails puts the claim back.
 */
@Service
public class SchoolSuggestionService {

  /** What a parent can do with an accepted event the school changed or removed. */
  public enum ReviewAction {
    /** Changed: move the calendar event to what the school now says. */
    APPLY,
    /** Changed or removed: leave the calendar event as it is. */
    KEEP,
    /** Removed: take the calendar event off. */
    REMOVE
  }

  private final MongoTemplate coparent;
  private final CalendarService calendar;
  private final ChildRepository children;
  private final CoparentAccessPolicy access;
  private final CoparentAuditService audits;
  private final Clock clock;

  public SchoolSuggestionService(
      @Qualifier("coparentMongoTemplate") final MongoTemplate coparent,
      final CalendarService calendar,
      final ChildRepository children,
      final CoparentAccessPolicy access,
      final CoparentAuditService audits,
      final Clock clock) {
    this.coparent = coparent;
    this.calendar = calendar;
    this.children = children;
    this.access = access;
    this.audits = audits;
    this.clock = clock;
  }

  /**
   * What is waiting for the family: new events, and accepted ones the school has since changed
   * or removed. Past events are left out.
   *
   * @param familyId the family
   * @return suggestions in date order
   */
  public List<SchoolEventSuggestion> waiting(final ObjectId familyId) {
    access.requireMember(familyId);
    final LocalDate today = LocalDate.now(clock.withZone(SchoolSuggestionSync.LONDON));
    final Criteria pending = Criteria.where("status").is(SchoolEventSuggestion.Status.PENDING);
    final Criteria review = Criteria.where("status").is(SchoolEventSuggestion.Status.ACCEPTED)
        .and("review").ne(null);
    return coparent.find(Query.query(Criteria.where("familyId").is(familyId)
            .and("details.endDate").gte(today).orOperator(pending, review))
        .with(Sort.by("details.startDate", "details.startTime")), SchoolEventSuggestion.class);
  }

  /**
   * Puts a suggested event on the calendar.
   *
   * @param familyId the family
   * @param suggestionId the suggestion
   * @return the suggestion as accepted
   */
  public SchoolEventSuggestion accept(final ObjectId familyId, final ObjectId suggestionId) {
    final Parent actor = access.requireMember(familyId);
    final SchoolEventSuggestion current = get(familyId, suggestionId);
    final List<ObjectId> liveChildren = liveChildren(familyId, current.childIds());
    if (liveChildren.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The children this event is for are no longer in the family");
    }
    final ObjectId eventId = new ObjectId();
    final Instant now = Instant.now(clock);
    final SchoolEventSuggestion claimed = coparent.findAndModify(
        Query.query(Criteria.where("_id").is(suggestionId).and("familyId").is(familyId)
            .and("status").is(SchoolEventSuggestion.Status.PENDING)),
        new Update().set("status", SchoolEventSuggestion.Status.ACCEPTED)
            .set("calendarEventId", eventId).set("decidedBy", actor.id())
            .set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), SchoolEventSuggestion.class);
    if (claimed == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This school event has already been decided");
    }
    try {
      calendar.createEvent(familyId, values(claimed.details(), liveChildren, null), eventId,
          null);
    } catch (RuntimeException failure) {
      coparent.updateFirst(Query.query(Criteria.where("_id").is(suggestionId)
              .and("status").is(SchoolEventSuggestion.Status.ACCEPTED)
              .and("calendarEventId").is(eventId)),
          new Update().set("status", SchoolEventSuggestion.Status.PENDING)
              .unset("calendarEventId").unset("decidedBy").unset("decidedAt")
              .set("updatedAt", Instant.now(clock)), SchoolEventSuggestion.class);
      throw failure;
    }
    audits.record(familyId, "school_event_suggestion", suggestionId, "accept",
        Map.of("calendarEventId", eventId));
    return claimed;
  }

  /**
   * Says no to a suggested event; it is never offered again.
   *
   * @param familyId the family
   * @param suggestionId the suggestion
   * @return the suggestion as dismissed
   */
  public SchoolEventSuggestion dismiss(final ObjectId familyId, final ObjectId suggestionId) {
    final Parent actor = access.requireMember(familyId);
    final Instant now = Instant.now(clock);
    final SchoolEventSuggestion saved = coparent.findAndModify(
        Query.query(Criteria.where("_id").is(suggestionId).and("familyId").is(familyId)
            .and("status").is(SchoolEventSuggestion.Status.PENDING)),
        new Update().set("status", SchoolEventSuggestion.Status.DISMISSED)
            .set("decidedBy", actor.id()).set("decidedAt", now).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true), SchoolEventSuggestion.class);
    if (saved == null) {
      get(familyId, suggestionId);
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This school event has already been decided");
    }
    audits.record(familyId, "school_event_suggestion", suggestionId, "dismiss", Map.of());
    return saved;
  }

  /**
   * Answers the question an accepted event was put back in the list with.
   *
   * @param familyId the family
   * @param suggestionId the suggestion
   * @param action what to do with the calendar event
   * @return the suggestion afterwards
   */
  public SchoolEventSuggestion resolveReview(final ObjectId familyId,
      final ObjectId suggestionId, final ReviewAction action) {
    access.requireMember(familyId);
    final SchoolEventSuggestion current = get(familyId, suggestionId);
    if (current.status() != SchoolEventSuggestion.Status.ACCEPTED || current.review() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to review");
    }
    final boolean changed = current.review() == SchoolEventSuggestion.Review.CHANGED;
    if (action == ReviewAction.APPLY && !changed || action == ReviewAction.REMOVE && changed) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That is not an answer to this review");
    }
    // Claimed first, matched on the review being asked, so two answers cannot both apply.
    final Update update = new Update().unset("review").unset("proposed")
        .set("updatedAt", Instant.now(clock));
    if (action == ReviewAction.APPLY) {
      update.set("details", current.proposed());
    }
    if (action == ReviewAction.REMOVE) {
      update.set("status", SchoolEventSuggestion.Status.WITHDRAWN);
    }
    final SchoolEventSuggestion saved = coparent.findAndModify(
        Query.query(Criteria.where("_id").is(suggestionId).and("familyId").is(familyId)
            .and("status").is(SchoolEventSuggestion.Status.ACCEPTED)
            .and("review").is(current.review())),
        update, FindAndModifyOptions.options().returnNew(true), SchoolEventSuggestion.class);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This has already been answered");
    }
    final CalendarEvent event = calendarEvent(familyId, current.calendarEventId());
    if (event != null && action == ReviewAction.APPLY) {
      calendar.updateEvent(familyId, event.id(), values(current.proposed(),
          liveChildren(familyId, event.childIds()), event));
    } else if (event != null && action == ReviewAction.REMOVE) {
      calendar.deleteEvent(familyId, event.id());
    }
    audits.record(familyId, "school_event_suggestion", suggestionId,
        "review_" + action.name().toLowerCase(Locale.ROOT), Map.of());
    return saved;
  }

  private SchoolEventSuggestion get(final ObjectId familyId, final ObjectId suggestionId) {
    final SchoolEventSuggestion found = coparent.findOne(Query.query(
            Criteria.where("_id").is(suggestionId).and("familyId").is(familyId)),
        SchoolEventSuggestion.class);
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "School event not found");
    }
    return found;
  }

  private CalendarEvent calendarEvent(final ObjectId familyId, final ObjectId eventId) {
    return eventId == null ? null : coparent.findOne(Query.query(Criteria.where("_id")
            .is(eventId).and("familyId").is(familyId).and("deletedAt").is(null)),
        CalendarEvent.class);
  }

  private List<ObjectId> liveChildren(final ObjectId familyId, final List<ObjectId> wanted) {
    final Set<ObjectId> live = children.findByFamilyIdAndDeletedAtIsNull(familyId).stream()
        .map(Child::id).collect(Collectors.toSet());
    return wanted.stream().filter(live::contains).toList();
  }

  /**
   * The calendar values for a school event. All-day dates are stored as midnight UTC, as the
   * calendar's own form sends them. Editing an accepted event keeps whatever the family set on it
   * that the school does not know about: the parents it is with, and the notes they wrote.
   */
  static CalendarService.EventValues values(final SchoolEventSuggestion.Details details,
      final List<ObjectId> childIds, final CalendarEvent existing) {
    final LocalDate end = details.endDate() == null ? details.startDate() : details.endDate();
    return new CalendarService.EventValues(
        existing == null ? "school" : existing.type(),
        existing == null ? details.title() : existing.title(),
        details.startDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
        end.atStartOfDay(ZoneOffset.UTC).toInstant(),
        details.startTime(),
        details.endTime(),
        details.allDay(),
        existing == null ? null : existing.parentId(),
        existing == null ? List.of() : existing.parentIds(),
        childIds.isEmpty() && existing != null ? existing.childIds() : childIds,
        details.location(),
        existing == null ? details.notes() : existing.notes(),
        null);
  }
}
