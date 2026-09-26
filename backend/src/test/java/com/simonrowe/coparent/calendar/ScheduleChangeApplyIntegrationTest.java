package com.simonrowe.coparent.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.EventRepository;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Approving a schedule change changes the calendar; a failed apply changes nothing. */
@TestPropertySource(properties = "coparent.enabled=true")
class ScheduleChangeApplyIntegrationTest extends AbstractIntegrationTest {

  private static final String REQUESTS =
      "/api/coparent/families/{familyId}/schedule-change-requests";

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.EVENTS,
      V043CreateCoparentCollections.CATEGORIES,
      V043CreateCoparentCollections.SCHEDULE_CHANGES,
      V043CreateCoparentCollections.AUDITS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private InvitationRepository invitations;

  @Autowired
  private EventRepository events;

  @BeforeEach
  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
  }

  @Test
  void approvedSwapMovesOneOffEventAndKeepsItsTimes() throws Exception {
    final Fixture fixture = family();
    final String eventId = createEvent(fixture, """
        {"type":"custody","title":"Alice's weekend","startDate":"2026-10-02T00:00:00Z",
         "endDate":"2026-10-04T00:00:00Z","startTime":"17:00","endTime":"18:00",
         "allDay":false,"childIds":["%s"]}
        """);
    final String requestId = request(fixture, eventId, "swap", "2026-10-02", "2026-10-04",
        "2026-10-09", "2026-10-11");

    approve(fixture, requestId).andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("approved")));

    final CalendarEvent moved = events.findById(new ObjectId(eventId)).orElseThrow();
    assertThat(moved.startDate()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
    assertThat(moved.endDate()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    assertThat(moved.startTime()).isEqualTo("17:00");
    assertThat(events.findAll()).hasSize(1);
  }

  @Test
  void approvedChangeToOneOccurrenceSkipsItAndAddsOneOffCopy() throws Exception {
    final Fixture fixture = family();
    final String eventId = createEvent(fixture, """
        {"type":"activity","title":"Swimming","startDate":"2026-09-28T00:00:00Z",
         "startTime":"18:30","endTime":"19:00","allDay":false,"childIds":["%s"],
         "recurring":{"frequency":"weekly","days":["monday"]}}
        """);
    final String requestId = request(fixture, eventId, "extend", "2026-10-26", "2026-10-26",
        "2026-10-27", "2026-10-27");

    approve(fixture, requestId).andExpect(status().isOk());

    final CalendarEvent series = events.findById(new ObjectId(eventId)).orElseThrow();
    assertThat(series.recurring().excludedDates()).containsExactly("2026-10-26");
    // The copy takes the request's id, which is what makes a retried approval add nothing.
    final CalendarEvent copy = events.findById(new ObjectId(requestId)).orElseThrow();
    assertThat(copy.recurring()).isNull();
    assertThat(copy.title()).isEqualTo("Swimming");
    assertThat(copy.startDate()).isEqualTo(Instant.parse("2026-10-27T00:00:00Z"));
    assertThat(copy.startTime()).isEqualTo("18:30");
  }

  @Test
  void approvedRemovalDeletesOneOffAndSkipsAnOccurrence() throws Exception {
    final Fixture fixture = family();
    final String oneOff = createEvent(fixture, """
        {"type":"school","title":"Trip","startDate":"2026-10-07T00:00:00Z","allDay":true,
         "childIds":["%s"]}
        """);
    final String series = createEvent(fixture, """
        {"type":"activity","title":"Cricket","startDate":"2026-09-30T00:00:00Z","allDay":false,
         "childIds":["%s"],"recurring":{"frequency":"weekly","days":["wednesday"]}}
        """);

    approve(fixture, request(fixture, oneOff, "remove", "2026-10-07", "2026-10-07",
        "2026-10-07", "2026-10-07")).andExpect(status().isOk());
    approve(fixture, request(fixture, series, "remove", "2026-10-14", "2026-10-14",
        "2026-10-14", "2026-10-14")).andExpect(status().isOk());

    assertThat(events.findById(new ObjectId(oneOff)).orElseThrow().deletedAt()).isNotNull();
    assertThat(events.findById(new ObjectId(series)).orElseThrow().recurring().excludedDates())
        .containsExactly("2026-10-14");
  }

  @Test
  void approvedAddWithoutEventCreatesCustodyBlockForTheRequester() throws Exception {
    final Fixture fixture = family();
    final String requestId = request(fixture, null, "add", null, null,
        "2026-10-16", "2026-10-18");

    approve(fixture, requestId).andExpect(status().isOk());

    final CalendarEvent custody = events.findById(new ObjectId(requestId)).orElseThrow();
    assertThat(custody.type()).isEqualTo("custody");
    assertThat(custody.title()).isEqualTo("Alice's time");
    assertThat(custody.parentId()).isEqualTo(new ObjectId(fixture.aliceId()));
    assertThat(custody.childIds()).containsExactly(new ObjectId(fixture.childId()));
    assertThat(custody.startDate()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
  }

  @Test
  void failedApplyUndoesPartialWritesAndLeavesRequestPending() throws Exception {
    final Fixture fixture = family();
    final String eventId = createEvent(fixture, """
        {"type":"activity","title":"Swimming","startDate":"2026-09-28T00:00:00Z",
         "allDay":false,"childIds":["%s"],"recurring":{"frequency":"weekly","days":["monday"]}}
        """);
    final String requestId = request(fixture, eventId, "swap", "2026-10-26", "2026-10-26",
        "2026-10-27", "2026-10-27");
    // The copy can no longer be created (its child is gone), after the skip has already
    // succeeded: the skip must be rolled back rather than silently losing the occurrence.
    mongoTemplate.getCollection(V043CreateCoparentCollections.CHILDREN).updateOne(
        new Document("_id", new ObjectId(fixture.childId())),
        new Document("$set", new Document("deletedAt", new java.util.Date())));

    approve(fixture, requestId).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message", containsString("still pending")));

    assertThat(events.findById(new ObjectId(eventId)).orElseThrow().recurring().excludedDates())
        .isEmpty();
    assertThat(events.findById(new ObjectId(requestId))).isEmpty();
    mockMvc.perform(get(REQUESTS + "/{id}", fixture.familyId(), requestId).with(user("bob")))
        .andExpect(jsonPath("$.status", is("pending")))
        .andExpect(jsonPath("$.resolvedBy").doesNotExist());
  }

  @Test
  void refusesRequestsThatCouldNotBeApplied() throws Exception {
    final Fixture fixture = family();
    final String series = createEvent(fixture, """
        {"type":"activity","title":"Cricket","startDate":"2026-09-30T00:00:00Z","allDay":false,
         "childIds":["%s"],"recurring":{"frequency":"weekly","days":["wednesday"]}}
        """);

    // Moving "the event" needs an event; moving a repeating one needs the occurrence.
    postRequest(fixture, """
        {"reason":"Trade days","proposedChange":{"type":"swap",
         "newStartDate":"2026-10-09","newEndDate":"2026-10-11"}}
        """).andExpect(status().isBadRequest());
    postRequest(fixture, """
        {"reason":"Move cricket","originalEventId":"%s","proposedChange":{"type":"extend",
         "newStartDate":"2026-10-15","newEndDate":"2026-10-15"}}
        """.formatted(series)).andExpect(status().isBadRequest());
  }

  @Test
  void declineChangesNothing() throws Exception {
    final Fixture fixture = family();
    final String eventId = createEvent(fixture, """
        {"type":"school","title":"Trip","startDate":"2026-10-07T00:00:00Z","allDay":true,
         "childIds":["%s"]}
        """);
    final String requestId = request(fixture, eventId, "remove", "2026-10-07", "2026-10-07",
        "2026-10-07", "2026-10-07");

    mockMvc.perform(post(REQUESTS + "/{id}/decline", fixture.familyId(), requestId)
            .with(user("bob")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("declined")));

    assertThat(events.findById(new ObjectId(eventId)).orElseThrow().deletedAt()).isNull();
  }

  private org.springframework.test.web.servlet.ResultActions approve(
      final Fixture fixture, final String requestId) throws Exception {
    return mockMvc.perform(post(REQUESTS + "/{id}/approve", fixture.familyId(), requestId)
        .with(user("bob")).contentType(MediaType.APPLICATION_JSON).content("{}"));
  }

  private String request(
      final Fixture fixture,
      final String eventId,
      final String type,
      final String originalStart,
      final String originalEnd,
      final String newStart,
      final String newEnd) throws Exception {
    final String body = """
        {"reason":"Needed for work","originalEventId":%s,"proposedChange":{"type":"%s",
         "originalStartDate":%s,"originalEndDate":%s,"newStartDate":"%s","newEndDate":"%s"}}
        """.formatted(quoted(eventId), type, quoted(originalStart), quoted(originalEnd),
        newStart, newEnd);
    final MvcResult created = postRequest(fixture, body).andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
  }

  private org.springframework.test.web.servlet.ResultActions postRequest(
      final Fixture fixture, final String body) throws Exception {
    return mockMvc.perform(post(REQUESTS, fixture.familyId()).with(user("alice"))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static String quoted(final String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private String createEvent(final Fixture fixture, final String template) throws Exception {
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(template.formatted(fixture.childId())))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
  }

  private Fixture family() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult familyResult = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String familyId = JsonPath.read(familyResult.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated());
    final Invitation invitation = invitations.findAll().getFirst();
    mockMvc.perform(post("/api/coparent/invitations/accept")
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isOk());
    final MvcResult childResult = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    final MvcResult profile = mockMvc.perform(get("/api/coparent/me").with(user("alice")))
        .andExpect(status().isOk()).andReturn();
    final List<String> aliceIds = JsonPath.read(profile.getResponse().getContentAsString(),
        "$.profiles[?(@.familyId=='" + familyId + "')].id");
    final String aliceId = aliceIds.getFirst();
    return new Fixture(familyId,
        JsonPath.read(childResult.getResponse().getContentAsString(), "$.id"), aliceId);
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId, String aliceId) {
  }
}
