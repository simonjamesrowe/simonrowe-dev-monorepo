package com.simonrowe.coparent.assistant;

import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.ConversationRepository;
import com.simonrowe.coparent.persistence.EventCategoryRepository;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.persistence.ScheduleChangeRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds the deliberately minimal, family-scoped context supplied to inference. */
@Component
public class AssistantContextFactory {

  private static final int MAX_EVENTS = 500;

  private final CoparentAccessPolicy access;
  private final FamilyRepository families;
  private final ParentRepository parents;
  private final ChildRepository children;
  private final EventCategoryRepository categories;
  private final EventRepository events;
  private final ScheduleChangeRepository changes;
  private final ConversationRepository conversations;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock;

  /** Creates the context factory using the application clock. */
  @Autowired
  public AssistantContextFactory(
      final CoparentAccessPolicy access,
      final FamilyRepository families,
      final ParentRepository parents,
      final ChildRepository children,
      final EventCategoryRepository categories,
      final EventRepository events,
      final ScheduleChangeRepository changes,
      final ConversationRepository conversations) {
    this(access, families, parents, children, categories, events, changes, conversations,
        Clock.systemUTC());
  }

  AssistantContextFactory(
      final CoparentAccessPolicy access,
      final FamilyRepository families,
      final ParentRepository parents,
      final ChildRepository children,
      final EventCategoryRepository categories,
      final EventRepository events,
      final ScheduleChangeRepository changes,
      final ConversationRepository conversations,
      final Clock clock) {
    this.access = access;
    this.families = families;
    this.parents = parents;
    this.children = children;
    this.categories = categories;
    this.events = events;
    this.changes = changes;
    this.conversations = conversations;
    this.clock = clock;
  }

  /** Authorises the family and serialises only the context fields approved for model use. */
  public Context build(final ObjectId familyId) {
    access.requireMember(familyId);
    final Family family = families.findByIdAndDeletedAtIsNull(familyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
    final ZoneId zone = safeZone(family.timeZone());
    final Instant now = clock.instant();
    final Instant earliest = ZonedDateTime.ofInstant(now, zone).minusDays(90).toInstant();
    final Instant latest = ZonedDateTime.ofInstant(now, zone).plusMonths(18).toInstant();
    final List<CalendarEvent> boundedEvents = events
        .findByFamilyIdAndDeletedAtIsNullOrderByStartDateAsc(familyId).stream()
        .filter(event -> !event.startDate().isBefore(earliest)
            && !event.startDate().isAfter(latest))
        .sorted(Comparator.comparingLong(event ->
            Math.abs(Duration.between(now, event.startDate()).toMillis())))
        .limit(MAX_EVENTS)
        .toList();

    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("currentDateTime", ZonedDateTime.ofInstant(now, zone).toString());
    data.put("timeZone", zone.getId());
    data.put("parents", parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE)
        .stream().map(parent -> Map.of("id", parent.id().toHexString(),
            "name", parent.fullName())).toList());
    data.put("children", children.findByFamilyIdAndDeletedAtIsNull(familyId).stream()
        .map(child -> Map.of("id", child.id().toHexString(), "name", child.fullName())).toList());
    data.put("categories", categories.findByFamilyIdAndDeletedAtIsNullOrderByNameAsc(familyId)
        .stream().map(category -> Map.of("id", category.id().toHexString(),
            "name", category.name(), "system", category.system())).toList());
    data.put("pendingScheduleRequests", changes
        .findByFamilyIdAndDeletedAtIsNullOrderByRequestedAtDesc(familyId).stream()
        .filter(change -> "pending".equals(change.status()))
        .map(change -> Map.of("id", change.id().toHexString(),
            "requestedBy", change.requestedBy().toHexString(), "reason", change.reason(),
            "updatedAt", change.updatedAt().toString())).toList());
    data.put("conversations", conversations
        .findByFamilyIdAndDeletedAtIsNullOrderByLastMessageAtDesc(familyId).stream()
        .map(conversation -> Map.of("id", conversation.id().toHexString(),
            "type", conversation.type(), "subject", conversation.subject(),
            "parentIds", List.of(conversation.parent1Id().toHexString(),
                conversation.parent2Id().toHexString()),
            "lastMessageAt", conversation.lastMessageAt().toString(),
            "updatedAt", conversation.updatedAt().toString())).toList());
    data.put("events", boundedEvents.stream().map(AssistantContextFactory::eventMetadata).toList());
    try {
      return new Context(objectMapper.writeValueAsString(data), family, now);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to serialize assistant context", exception);
    }
  }

  private static Map<String, Object> eventMetadata(final CalendarEvent event) {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", event.id().toHexString());
    result.put("type", event.type());
    result.put("title", event.title());
    result.put("startDate", event.startDate().toString());
    result.put("endDate", event.endDate() == null ? null : event.endDate().toString());
    result.put("allDay", event.allDay());
    result.put("parentIds", event.parentIds().stream().map(ObjectId::toHexString).toList());
    result.put("childIds", event.childIds().stream().map(ObjectId::toHexString).toList());
    result.put("location", event.location());
    result.put("updatedAt", event.updatedAt().toString());
    return result;
  }

  private static ZoneId safeZone(final String value) {
    try {
      return value == null || value.isBlank() ? ZoneId.of("Europe/London") : ZoneId.of(value);
    } catch (java.time.DateTimeException exception) {
      return ZoneId.of("Europe/London");
    }
  }

  public record Context(String json, Family family, Instant generatedAt) {
  }
}
