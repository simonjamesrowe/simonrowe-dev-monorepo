package com.simonrowe.coparent.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.simonrowe.coparent.model.CalendarEvent;
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
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Branch-level tests for validation and guarded calendar state transitions. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarServiceValidationTest {

  private static final ObjectId FAMILY_ID = new ObjectId();
  private static final ObjectId CHILD_ID = new ObjectId();
  private static final ObjectId PARENT_ID = new ObjectId();
  private static final Instant START = Instant.parse("2026-10-02T15:00:00Z");

  @Mock private EventRepository events;
  @Mock private EventCategoryRepository categories;
  @Mock private ScheduleChangeRepository changes;
  @Mock private ParentRepository parents;
  @Mock private ChildRepository children;
  @Mock private CoparentAccessPolicy access;
  @Mock private CoparentAuditService audits;
  @Mock private MongoTemplate mongoTemplate;

  private CalendarService service;
  private Parent actor;

  @BeforeEach
  void setUp() {
    service = new CalendarService(events, categories, changes, parents, children, access, audits,
        mongoTemplate);
    actor = new Parent(PARENT_ID, "auth0|alice", FAMILY_ID, "Alice", "alice@example.com",
        "primary", "active", null, null, START, START, START);
    lenient().when(access.requireMember(FAMILY_ID)).thenReturn(actor);
    lenient().when(children.countByIdInAndFamilyIdAndDeletedAtIsNull(
        List.of(CHILD_ID), FAMILY_ID)).thenReturn(1L);
  }

  @Test
  void rejectsInvalidEventShapes() {
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event(null, "Pickup", START, null,
        List.of(CHILD_ID), null, List.of(), null)));
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", " ", START, null,
        List.of(CHILD_ID), null, List.of(), null)));
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", null, null,
        List.of(CHILD_ID), null, List.of(), null)));
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", START,
        START.minusSeconds(1), List.of(CHILD_ID), null, List.of(), null)));
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", START, null,
        List.of(), null, List.of(), null)));

    when(children.countByIdInAndFamilyIdAndDeletedAtIsNull(List.of(CHILD_ID), FAMILY_ID))
        .thenReturn(0L, 1L, 1L);
    assertBadRequest(() -> service.createEvent(FAMILY_ID, validEvent()));

    when(parents.findByIdAndFamilyIdAndStatus(PARENT_ID, FAMILY_ID, "active"))
        .thenReturn(Optional.empty());
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", START, null,
        List.of(CHILD_ID), PARENT_ID, List.of(), null)));

    when(parents.countByIdInAndFamilyIdAndStatus(List.of(PARENT_ID), FAMILY_ID, "active"))
        .thenReturn(0L);
    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", START, null,
        List.of(CHILD_ID), null, List.of(PARENT_ID), null)));

    assertBadRequest(() -> service.createEvent(FAMILY_ID, event("school", "Pickup", START, null,
        List.of(CHILD_ID), null, List.of(),
        new CalendarEvent.Recurring("monthly", List.of()))));
  }

  @Test
  void normalisesMissingEventAssociations() {
    final CalendarService.EventValues values = new CalendarService.EventValues(
        "school", "Pickup", START, null, null, null, true, null, null, null, null, null, null);

    assertThat(values.parentIds()).isEmpty();
    assertThat(values.childIds()).isEmpty();
  }

  @Test
  void validatesAndProtectsCategories() {
    assertBadRequest(() -> service.createCategory(FAMILY_ID,
        new CalendarService.CategoryValues(null, "book", "#fff", false)));
    assertBadRequest(() -> service.createCategory(FAMILY_ID,
        new CalendarService.CategoryValues("School", " ", "#fff", false)));

    final ObjectId categoryId = new ObjectId();
    final EventCategory system = category(categoryId, true);
    when(categories.findByIdAndFamilyIdAndDeletedAtIsNull(categoryId, FAMILY_ID))
        .thenReturn(Optional.of(system));
    assertBadRequest(() -> service.updateCategory(FAMILY_ID, categoryId,
        new CalendarService.CategoryValues("School", "book", "#fff", false)));
    assertBadRequest(() -> service.deleteCategory(FAMILY_ID, categoryId));

    final EventCategory custom = category(categoryId, false);
    when(categories.findByIdAndFamilyIdAndDeletedAtIsNull(categoryId, FAMILY_ID))
        .thenReturn(Optional.of(custom));
    when(categories.save(any(EventCategory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    final EventCategory updated = service.updateCategory(FAMILY_ID, categoryId,
        new CalendarService.CategoryValues(" Clubs ", " star ", "#123456", true));
    assertThat(updated.name()).isEqualTo("Clubs");
    assertThat(updated.icon()).isEqualTo("star");
    assertThat(updated.defaultCategory()).isTrue();
  }

  @Test
  void validatesScheduleChangesAndWithdrawals() {
    assertBadRequest(() -> service.createChange(FAMILY_ID, null, proposed("swap", "2026-10-02",
        "2026-10-03"), " "));
    assertBadRequest(() -> service.createChange(FAMILY_ID, null, null, "Needed"));
    assertBadRequest(() -> service.createChange(FAMILY_ID, null,
        proposed("unknown", "2026-10-02", "2026-10-03"), "Needed"));
    assertBadRequest(() -> service.createChange(FAMILY_ID, null,
        proposed("swap", null, "2026-10-03"), "Needed"));
    assertBadRequest(() -> service.createChange(FAMILY_ID, null,
        proposed("swap", "2026-10-03", "2026-10-02"), "Needed"));
    assertBadRequest(() -> service.createChange(FAMILY_ID, null,
        proposed("swap", "not-a-date", "2026-10-03"), "Needed"));
    assertBadRequest(() -> service.resolveChange(FAMILY_ID, new ObjectId(), "maybe", null));

    final ObjectId requestId = new ObjectId();
    final ObjectId eventId = new ObjectId();
    when(events.findByIdAndFamilyIdAndDeletedAtIsNull(eventId, FAMILY_ID))
        .thenReturn(Optional.of(calendarEvent(eventId)));
    when(changes.save(any(ScheduleChangeRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    assertThat(service.createChange(FAMILY_ID, eventId,
        proposed("extend", "2026-10-02", "2026-10-03"), " Longer visit ").reason())
        .isEqualTo("Longer visit");
    when(changes.findByFamilyIdAndDeletedAtIsNullOrderByRequestedAtDesc(FAMILY_ID))
        .thenReturn(List.of());
    assertThat(service.listChanges(FAMILY_ID)).isEmpty();

    when(changes.findByIdAndFamilyIdAndDeletedAtIsNull(requestId, FAMILY_ID))
        .thenReturn(Optional.of(change(requestId, new ObjectId(), "approved")));
    assertConflict(() -> service.resolveChange(FAMILY_ID, requestId, "declined", null));

    when(changes.findByIdAndFamilyIdAndDeletedAtIsNull(requestId, FAMILY_ID))
        .thenReturn(Optional.of(change(requestId, new ObjectId(), "pending")));
    assertForbidden(() -> service.withdrawChange(FAMILY_ID, requestId));

    when(changes.findByIdAndFamilyIdAndDeletedAtIsNull(requestId, FAMILY_ID))
        .thenReturn(Optional.of(change(requestId, PARENT_ID, "approved")));
    assertConflict(() -> service.withdrawChange(FAMILY_ID, requestId));

    when(changes.findByIdAndFamilyIdAndDeletedAtIsNull(requestId, FAMILY_ID))
        .thenReturn(Optional.of(change(requestId, PARENT_ID, "pending")));
    service.withdrawChange(FAMILY_ID, requestId);
  }

  private CalendarService.EventValues validEvent() {
    return event("school", "Pickup", START, null, List.of(CHILD_ID), null, List.of(), null);
  }

  private CalendarService.EventValues event(
      final String type,
      final String title,
      final Instant start,
      final Instant end,
      final List<ObjectId> childIds,
      final ObjectId parentId,
      final List<ObjectId> parentIds,
      final CalendarEvent.Recurring recurring) {
    return new CalendarService.EventValues(type, title, start, end, null, null, true, parentId,
        parentIds, childIds, null, null, recurring);
  }

  private EventCategory category(final ObjectId id, final boolean system) {
    return new EventCategory(id, FAMILY_ID, "School", "book", "#fff", false, system, null,
        START, START);
  }

  private CalendarEvent calendarEvent(final ObjectId id) {
    return new CalendarEvent(id, FAMILY_ID, "school", "Pickup", START, null, null, null, true,
        null, List.of(), List.of(CHILD_ID), null, null, null, null, START, START);
  }

  private ScheduleChangeRequest.ProposedChange proposed(
      final String type,
      final String start,
      final String end) {
    return new ScheduleChangeRequest.ProposedChange(type, null, null, start, end);
  }

  private ScheduleChangeRequest change(
      final ObjectId id,
      final ObjectId requestedBy,
      final String status) {
    return new ScheduleChangeRequest(id, FAMILY_ID, status, requestedBy, START, null, null, null,
        proposed("swap", "2026-10-02", "2026-10-03"), "Needed", null, null, START, START);
  }

  private void assertBadRequest(final Runnable operation) {
    assertStatus(operation, HttpStatus.BAD_REQUEST);
  }

  private void assertForbidden(final Runnable operation) {
    assertStatus(operation, HttpStatus.FORBIDDEN);
  }

  private void assertConflict(final Runnable operation) {
    assertStatus(operation, HttpStatus.CONFLICT);
  }

  private void assertStatus(final Runnable operation, final HttpStatus expected) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(expected));
  }
}
