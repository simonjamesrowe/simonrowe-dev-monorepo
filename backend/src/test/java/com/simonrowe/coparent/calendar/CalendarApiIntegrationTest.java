package com.simonrowe.coparent.calendar;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import java.util.List;
import org.bson.Document;
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

/** Calendar, recurrence, category and schedule-decision contracts against real MongoDB. */
@TestPropertySource(properties = "coparent.enabled=true")
class CalendarApiIntegrationTest extends AbstractIntegrationTest {

  private static final String SKIPPED_DATE =
      "/api/coparent/families/{familyId}/events/{eventId}/skipped-dates/{date}";

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

  @BeforeEach
  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
  }

  @Test
  void createsUpdatesAndSoftDeletesRecurringEvent() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventJson(fixture.childId(), "School pickup")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.recurring.frequency", is("weekly")))
        .andReturn();
    final String eventId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(put("/api/coparent/families/{familyId}/events/{eventId}",
            fixture.familyId(), eventId)
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventJson(fixture.childId(), "Updated pickup")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title", is("Updated pickup")));

    mockMvc.perform(get("/api/coparent/families/{familyId}/events/{eventId}",
            fixture.familyId(), eventId).with(user("mallory")))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/coparent/families/{familyId}/events/{eventId}",
            fixture.familyId(), eventId).with(user("alice")))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void skipsAndRestoresSingleOccurrencesOfOpenEndedSeries() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"activity","title":"Swimming","startDate":"2026-09-28T00:00:00Z",
                 "startTime":"18:30","endTime":"19:00","allDay":false,"childIds":["%s"],
                 "recurring":{"frequency":"weekly","days":["monday"]}}
                """.formatted(fixture.childId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.endDate").doesNotExist())
        .andExpect(jsonPath("$.recurring.excludedDates", hasSize(0)))
        .andReturn();
    final String eventId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    final String skipped = SKIPPED_DATE;

    mockMvc.perform(put(skipped, fixture.familyId(), eventId, "2026-11-02").with(user("bob")))
        .andExpect(status().isOk());
    mockMvc.perform(put(skipped, fixture.familyId(), eventId, "2026-10-26").with(user("alice")))
        .andExpect(status().isOk());
    mockMvc.perform(put(skipped, fixture.familyId(), eventId, "2026-10-26").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recurring.excludedDates",
            contains("2026-10-26", "2026-11-02")));

    // A whole-event edit that carries the skipped dates back keeps them.
    mockMvc.perform(put("/api/coparent/families/{familyId}/events/{eventId}",
            fixture.familyId(), eventId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"activity","title":"Swimming lessons",
                 "startDate":"2026-09-28T00:00:00Z","startTime":"18:30","endTime":"19:00",
                 "allDay":false,"childIds":["%s"],"recurring":{"frequency":"weekly",
                 "days":["monday"],"excludedDates":["2026-11-02","2026-10-26"]}}
                """.formatted(fixture.childId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endDate").doesNotExist())
        .andExpect(jsonPath("$.recurring.excludedDates",
            contains("2026-10-26", "2026-11-02")));

    mockMvc.perform(delete(skipped, fixture.familyId(), eventId, "2026-11-02")
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title", is("Swimming lessons")))
        .andExpect(jsonPath("$.recurring.excludedDates", contains("2026-10-26")));

    mockMvc.perform(put(skipped, fixture.familyId(), eventId, "2026-13-40").with(user("alice")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put(skipped, fixture.familyId(), eventId, "2026-10-19")
            .with(user("mallory")))
        .andExpect(status().isNotFound());
  }

  @Test
  void refusesToSkipAnOccurrenceOfOneOffEvent() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"school","title":"Photo day","startDate":"2026-10-02T00:00:00Z",
                 "allDay":true,"childIds":["%s"]}
                """.formatted(fixture.childId())))
        .andExpect(status().isCreated()).andReturn();
    final String eventId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(put(SKIPPED_DATE, fixture.familyId(), eventId, "2026-10-02")
            .with(user("alice")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void capsSkippedDatesInTheSameWriteThatAddsThem() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/events", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventJson(fixture.childId(), "School pickup")))
        .andExpect(status().isCreated()).andReturn();
    final String eventId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    final List<String> full = java.util.stream.IntStream.range(0,
            CalendarService.MAX_EXCLUDED_DATES)
        .mapToObj(week -> java.time.LocalDate.of(2026, 10, 2).plusWeeks(week).toString())
        .toList();
    mongoTemplate.getCollection(V043CreateCoparentCollections.EVENTS).updateOne(
        new Document("_id", new org.bson.types.ObjectId(eventId)),
        new Document("$set", new Document("recurring.excludedDates", full)));

    mockMvc.perform(put(SKIPPED_DATE, fixture.familyId(), eventId, "2030-01-04")
            .with(user("alice")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put(SKIPPED_DATE, fixture.familyId(), eventId, full.getFirst())
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recurring.excludedDates",
            hasSize(CalendarService.MAX_EXCLUDED_DATES)));
  }

  @Test
  void otherParentCanApproveButRequesterCannot() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/schedule-change-requests", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"reason":"Extra weekend","proposedChange":{"type":"add",
                 "newStartDate":"2026-10-02","newEndDate":"2026-10-04"}}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String requestId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(post(
            "/api/coparent/families/{familyId}/schedule-change-requests/{id}/approve",
            fixture.familyId(), requestId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post(
            "/api/coparent/families/{familyId}/schedule-change-requests/{id}/approve",
            fixture.familyId(), requestId)
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"responseNote\":\"Works for me\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("approved")))
        .andExpect(jsonPath("$.responseNote", is("Works for me")));
  }

  @Test
  void managesFamilyEventCategories() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/event-categories", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"School\",\"icon\":\"book\",\"color\":\"#0d9488\"}"))
        .andExpect(status().isCreated()).andReturn();
    final String categoryId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(get("/api/coparent/families/{familyId}/event-categories",
            fixture.familyId()).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
    mockMvc.perform(delete("/api/coparent/families/{familyId}/event-categories/{id}",
            fixture.familyId(), categoryId).with(user("alice")))
        .andExpect(status().isNoContent());
  }

  private Fixture familyWithTwoParentsAndChild() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult familyResult = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice"}
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
    return new Fixture(familyId,
        JsonPath.read(childResult.getResponse().getContentAsString(), "$.id"));
  }

  private String eventJson(final String childId, final String title) {
    return """
        {"type":"school","title":"%s","startDate":"2026-10-02T15:00:00Z",
         "endDate":"2026-10-02T16:00:00Z","allDay":false,"childIds":["%s"],
         "parentIds":[],"recurring":{"frequency":"weekly","days":["friday"]}}
        """.formatted(title, childId);
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId) {
  }
}
