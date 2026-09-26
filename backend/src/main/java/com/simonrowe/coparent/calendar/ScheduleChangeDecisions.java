package com.simonrowe.coparent.calendar;

import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decides a schedule-change request and, on approval, makes the calendar say what was agreed.
 *
 * <p>Approval used to flip a status and nothing else, so an "approved" swap left both parents
 * looking at the old dates. It now applies the change through the calendar's own validated
 * operations:
 *
 * <ul>
 *   <li>swap / extend — a one-off event moves to the new dates (times kept); for a repeating
 *       event the requested occurrence is skipped and a one-off copy is added on the new dates;
 *   <li>remove — a one-off event is deleted; a repeating event skips the occurrence;
 *   <li>add — a one-off copy of the event on the new dates, or, with no event, a custody block
 *       for the requester.
 * </ul>
 *
 * <p>The database runs without transactions, so claiming the request and writing the calendar
 * are separate operations. The claim comes first (so two approvers cannot both apply), every
 * write is idempotent (a created copy takes the request's own id), and if applying fails the
 * calendar writes already made are undone in reverse and the approval is put back to pending
 * with the reason, rather than leaving "approved" on a calendar that did not change (or changed
 * halfway: a swap on a repeating event is a skip and a copy). What remains is a process crash
 * mid-apply, which leaves the request approved and the calendar partly written. Re-applying is
 * safe because of the idempotency, but nothing does it automatically; that is the accepted cost
 * of not running a replica set.
 */
@Service
public class ScheduleChangeDecisions {

  private static final Logger log = LoggerFactory.getLogger(ScheduleChangeDecisions.class);

  private final CalendarService calendar;
  private final EventRepository events;
  private final ChildRepository children;
  private final ParentRepository parents;

  /** Creates the decision service over the calendar's validated operations. */
  public ScheduleChangeDecisions(
      final CalendarService calendar,
      final EventRepository events,
      final ChildRepository children,
      final ParentRepository parents) {
    this.calendar = calendar;
    this.events = events;
    this.children = children;
    this.parents = parents;
  }

  /** Approves or declines; an approval is applied to the calendar before it is reported. */
  public ScheduleChangeRequest resolve(
      final ObjectId familyId,
      final ObjectId requestId,
      final String decision,
      final String responseNote) {
    final ScheduleChangeRequest resolved =
        calendar.resolveChange(familyId, requestId, decision, responseNote);
    if (!"approved".equals(decision)) {
      return resolved;
    }
    final Deque<Runnable> undo = new ArrayDeque<>();
    try {
      apply(familyId, resolved, undo);
    } catch (RuntimeException exception) {
      while (!undo.isEmpty()) {
        try {
          undo.pop().run();
        } catch (RuntimeException undoFailure) {
          log.warn("Could not undo part of schedule change {}", requestId, undoFailure);
        }
      }
      calendar.reopenChange(familyId, requestId, resolved.resolvedBy());
      final String why = exception instanceof ResponseStatusException status
          && status.getReason() != null ? status.getReason() : "the calendar could not be updated";
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The change could not be applied, so the request is still pending: " + why, exception);
    }
    return resolved;
  }

  private void apply(
      final ObjectId familyId, final ScheduleChangeRequest request, final Deque<Runnable> undo) {
    final ScheduleChangeRequest.ProposedChange change = request.proposedChange();
    if (request.originalEventId() == null) {
      if ("add".equals(change.type())) {
        addCustody(familyId, request, undo);
      }
      // A request filed before an event was required has nothing to apply it to; approving
      // it records the decision, which is all it could ever mean.
      return;
    }
    final CalendarEvent original = events
        .findByIdAndFamilyIdAndDeletedAtIsNull(request.originalEventId(), familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "the event it changes no longer exists"));
    switch (change.type()) {
      case "swap", "extend" -> {
        if (original.recurring() == null) {
          calendar.updateEvent(familyId, original.id(), moved(original, change));
          undo.push(() -> events.save(original));
        } else {
          skip(familyId, original, change.originalStartDate(), undo);
          addCopy(familyId, request, original, undo);
        }
      }
      case "remove" -> {
        if (original.recurring() == null) {
          calendar.deleteEvent(familyId, original.id());
          undo.push(() -> events.save(original));
        } else {
          skip(familyId, original, change.originalStartDate(), undo);
        }
      }
      case "add" -> addCopy(familyId, request, original, undo);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "unsupported change type");
    }
  }

  private void skip(
      final ObjectId familyId,
      final CalendarEvent original,
      final String date,
      final Deque<Runnable> undo) {
    final boolean alreadySkipped = original.recurring().excludedDates().contains(date);
    calendar.skipOccurrence(familyId, original.id(), date);
    if (!alreadySkipped) {
      undo.push(() -> calendar.restoreOccurrence(familyId, original.id(), date));
    }
  }

  /** A one-off copy on the new dates. Keyed on the request id, so a retry cannot add two. */
  private void addCopy(
      final ObjectId familyId,
      final ScheduleChangeRequest request,
      final CalendarEvent original,
      final Deque<Runnable> undo) {
    if (events.existsById(request.id())) {
      return;
    }
    final CalendarService.EventValues values = moved(original, request.proposedChange());
    calendar.createEvent(familyId, new CalendarService.EventValues(values.type(), values.title(),
        values.startDate(), values.endDate(), values.startTime(), values.endTime(),
        values.allDay(), values.parentId(), values.parentIds(), values.childIds(),
        values.location(), values.notes(), null), request.id(), null);
    undo.push(() -> events.deleteById(request.id()));
  }

  private void addCustody(
      final ObjectId familyId, final ScheduleChangeRequest request, final Deque<Runnable> undo) {
    if (events.existsById(request.id())) {
      return;
    }
    final Parent requester = parents
        .findByIdAndFamilyIdAndStatus(request.requestedBy(), familyId, CoparentAccessPolicy.ACTIVE)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "the requesting parent is no longer in this family"));
    final var childIds = children.findByFamilyIdAndDeletedAtIsNull(familyId).stream()
        .map(Child::id).toList();
    final ScheduleChangeRequest.ProposedChange change = request.proposedChange();
    calendar.createEvent(familyId, new CalendarService.EventValues("custody",
        firstName(requester) + "'s time", day(change.newStartDate()), day(change.newEndDate()),
        null, null, true, requester.id(), java.util.List.of(), childIds, null,
        request.reason(), null), request.id(), null);
    undo.push(() -> events.deleteById(request.id()));
  }

  private static CalendarService.EventValues moved(
      final CalendarEvent event, final ScheduleChangeRequest.ProposedChange change) {
    return new CalendarService.EventValues(event.type(), event.title(),
        day(change.newStartDate()), day(change.newEndDate()), event.startTime(),
        event.endTime(), event.allDay(), event.parentId(), event.parentIds(), event.childIds(),
        event.location(), event.notes(), event.recurring());
  }

  // Calendar dates are stored as UTC midnight, the same instant the browser sends for a day.
  private static Instant day(final String value) {
    return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static String firstName(final Parent parent) {
    final String name = parent.fullName() == null ? "" : parent.fullName().trim();
    final int space = name.indexOf(' ');
    return space > 0 ? name.substring(0, space) : name.isEmpty() ? "Parent" : name;
  }
}
