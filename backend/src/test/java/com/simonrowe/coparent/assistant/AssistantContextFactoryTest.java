package com.simonrowe.coparent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Conversation;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.ConversationRepository;
import com.simonrowe.coparent.persistence.EventCategoryRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.ExpenseRepository;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.persistence.ScheduleChangeRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssistantContextFactoryTest {

  @Test
  void boundsEventsAndExcludesSensitiveBodies() throws Exception {
    final Instant now = Instant.parse("2026-09-22T10:00:00Z");
    final ObjectId familyId = new ObjectId();
    final ObjectId parentId = new ObjectId();
    final CoparentAccessPolicy access = mock(CoparentAccessPolicy.class);
    final FamilyRepository families = mock(FamilyRepository.class);
    final ParentRepository parents = mock(ParentRepository.class);
    final ChildRepository children = mock(ChildRepository.class);
    final EventCategoryRepository categories = mock(EventCategoryRepository.class);
    final EventRepository events = mock(EventRepository.class);
    final ScheduleChangeRepository changes = mock(ScheduleChangeRepository.class);
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ExpenseRepository expenses = mock(ExpenseRepository.class);
    when(families.findByIdAndDeletedAtIsNull(familyId)).thenReturn(Optional.of(
        new Family(familyId, "Home", "Europe/London", List.of(parentId), List.of(), List.of(),
            null, now, now)));
    when(parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE)).thenReturn(List.of(
        new Parent(parentId, "auth0|one", familyId, "Alex", "alex@example.com", "primary",
            "active", null, null, null, now, now)));
    when(children.findByFamilyIdAndDeletedAtIsNull(familyId)).thenReturn(List.of(
        new Child(new ObjectId(), familyId, "Sam", LocalDate.parse("2017-01-01"), "School",
            "sensitive medical note", null, null, now, now)));
    when(categories.findByFamilyIdAndDeletedAtIsNullOrderByNameAsc(familyId))
        .thenReturn(List.of());
    when(changes.findByFamilyIdAndDeletedAtIsNullOrderByRequestedAtDesc(familyId))
        .thenReturn(List.of());
    when(conversations.findByFamilyIdAndDeletedAtIsNullOrderByLastMessageAtDesc(familyId))
        .thenReturn(List.of(new Conversation(new ObjectId(), familyId, "message", "School",
            parentId, new ObjectId(), List.of(new Conversation.Message(new ObjectId(), parentId,
                "private prior message", now, List.of(parentId))), null, Map.of(), now, null,
            null, now, now)));
    final List<CalendarEvent> allEvents = new ArrayList<>();
    for (int index = 0; index < 501; index++) {
      final Instant start = now.plusSeconds(index * 60L);
      allEvents.add(new CalendarEvent(new ObjectId(), familyId, "school", "Event " + index,
          start, null, null, null, true, parentId, List.of(parentId), List.of(new ObjectId()),
          null, "private note", null, null, null, now, now));
    }
    when(events.findByFamilyIdAndDeletedAtIsNullOrderByStartDateAsc(familyId))
        .thenReturn(allEvents);
    final Parent alex = new Parent(parentId, "auth0|one", familyId, "Alex", "alex@example.com",
        "primary", "active", null, null, null, now, now);
    when(access.requireMember(familyId)).thenReturn(alex);
    final Expense open = expense(familyId, parentId, "Dentist", Expense.OUTSTANDING, now);
    when(expenses.findByFamilyIdAndDeletedAtIsNullOrderByDateDescCreatedAtDesc(familyId))
        .thenReturn(List.of(open,
            expense(familyId, parentId, "Settled long ago", Expense.REIMBURSED, now)));
    final AssistantContextFactory factory = new AssistantContextFactory(access, families, parents,
        children, categories, events, changes, conversations, expenses,
        Clock.fixed(now, ZoneOffset.UTC));

    final String json = factory.build(familyId).json();

    assertThat(json).doesNotContain("sensitive medical note", "private prior message",
        "private note", "alex@example.com", "private expense note", "Settled long ago");
    final var tree = new ObjectMapper().readTree(json);
    assertThat(tree.get("signedInParentId").asString()).isEqualTo(parentId.toHexString());
    assertThat(tree.get("openExpenses").size()).isEqualTo(1);
    assertThat(tree.get("openExpenses").get(0).get("id").asString())
        .isEqualTo(open.id().toHexString());
    assertThat(tree.get("openExpenses").get(0).get("amountPounds").asString())
        .isEqualTo("60.00");
    assertThat(new ObjectMapper().readTree(json).get("events").size()).isEqualTo(500);
    assertThat(json).contains("2026-09-22T11:00+01:00[Europe/London]");
  }

  private static Expense expense(final ObjectId familyId, final ObjectId payer,
      final String title, final String reimbursement, final Instant now) {
    return new Expense(new ObjectId(), familyId, title, "medical", List.of(new ObjectId()),
        6000, Expense.GBP, Expense.PAID, LocalDate.parse("2026-09-20"), payer,
        List.of(new Expense.Share(payer, 50), new Expense.Share(new ObjectId(), 50)),
        new Expense.Agreement(Expense.AGREED, payer, null, null, null),
        new Expense.Reimbursement(reimbursement, null, null, null, null, null), List.of(),
        "private expense note", List.of(), 2, payer, null, null, now, now);
  }
}
