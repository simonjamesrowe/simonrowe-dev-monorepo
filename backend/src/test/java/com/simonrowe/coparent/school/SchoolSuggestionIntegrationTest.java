package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V067CreateCoparentSchoolSuggestions;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * School events reach a family's calendar end to end, against real MongoDB: offered to the right
 * child, emailed once, accepted onto the calendar by either parent, never offered twice, and asked
 * about again when the school changes or removes an accepted one.
 *
 * <p>Every name is invented; the repository is public.
 */
@TestPropertySource(properties = {
    "coparent.enabled=true",
    "coparent.school-calendar.enabled=true",
    "coparent.school-calendar.ai-enabled=false"})
class SchoolSuggestionIntegrationTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.EVENTS,
      V043CreateCoparentCollections.CATEGORIES,
      V043CreateCoparentCollections.AUDITS,
      V067CreateCoparentSchoolSuggestions.SUGGESTIONS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate coparent;

  @Autowired
  private InvitationRepository invitations;

  @Autowired
  private ChildRepository children;

  @Autowired
  private SchoolEventRepository schoolEvents;

  @Autowired
  private SchoolSuggestionSync sync;

  @Autowired
  private ParentRepository parents;

  @Autowired
  private CoparentProperties coparentProperties;

  @MockitoBean
  private SchoolSuggestionMailer mailer;

  /** A school day a few days ahead, inside one academic year whatever today is. */
  private LocalDate day;

  @BeforeEach
  void setUp() {
    clean();
    V067CreateCoparentSchoolSuggestions.createIndexes(coparent);
    final LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
    final LocalDate soon = today.plusDays(3);
    // Late August would put "soon" in the next school year; start that week in September.
    day = AcademicYear.of(soon).equals(AcademicYear.of(soon.plusDays(10)))
        ? soon : LocalDate.of(soon.getYear(), Month.SEPTEMBER, 5);
  }

  @AfterEach
  void clean() {
    COLLECTIONS.forEach(collection -> coparent.getCollection(collection)
        .deleteMany(new Document()));
    schoolEvents.deleteAll();
    reset(mailer);
  }

  @Test
  void offersEachChildTheirOwnEventsAndEmailsOnce() throws Exception {
    final Fixture f = family();
    schoolEvents.saveAll(List.of(
        event("a", "Harriet's class trip", List.of(), null),
        event("b", "Omar's class trip", List.of(), null),
        event("c", "Year 6 Winter Sing Along", List.of("Year 6"), "3 PM"),
        event("d", "Kilmorie Winter Fair", List.of(), "2:00pm – 5:00pm"),
        event("e", "Year 1 Christmas Fair", List.of("Year 1"), null),
        restricted("f", "A letter only this family was sent", List.of())));

    final SchoolSuggestionSync.Report report = sync.run();

    assertThat(report.created()).isEqualTo(3);
    final ArgumentCaptor<List<SchoolEventSuggestion>> mailed = mailedItems();
    verify(mailer).needsApproval(anyList(), mailed.capture(), anyMap());
    assertThat(mailed.getValue()).extracting(s -> s.details().title())
        .containsExactlyInAnyOrder("Harriet's class trip", "Year 6 Winter Sing Along",
            "Winter Fair");

    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[?(@.details.title == \"Harriet's class trip\")].childIds[*]")
            .value(f.younger()))
        .andExpect(jsonPath("$[?(@.details.title == 'Winter Fair')].childIds[*]", hasSize(2)))
        .andExpect(jsonPath("$[?(@.details.title == 'Winter Fair')].details.startTime")
            .value("14:00"));
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("mallory")))
        .andExpect(status().isNotFound());

    reset(mailer);
    assertThat(sync.run().created()).isZero();
    verify(mailer, never()).needsApproval(anyList(), anyList(), anyMap());
  }

  @Test
  void eitherParentAcceptsOntoTheCalendarAndDismissedEventNeverComesBack() throws Exception {
    final Fixture f = family();
    schoolEvents.saveAll(List.of(
        event("a", "Harriet's class trip", List.of(), "09.15 to 10.45am"),
        event("b", "Winter Fair", List.of(), null)));
    sync.run();
    final String trip = suggestionId(f, "Harriet's class trip");
    final String fair = suggestionId(f, "Winter Fair");

    mockMvc.perform(post(path(f, trip, "accept")).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("accepted")));
    mockMvc.perform(post(path(f, trip, "accept")).with(user("alice")))
        .andExpect(status().isConflict());
    mockMvc.perform(post(path(f, fair, "dismiss")).with(user("alice")))
        .andExpect(status().isOk());

    final List<CalendarEvent> onCalendar = coparent.findAll(CalendarEvent.class);
    assertThat(onCalendar).hasSize(1);
    assertThat(onCalendar.getFirst().type()).isEqualTo("school");
    assertThat(onCalendar.getFirst().startTime()).isEqualTo("09:15");
    assertThat(onCalendar.getFirst().endTime()).isEqualTo("10:45");
    assertThat(onCalendar.getFirst().childIds()).containsExactly(new ObjectId(f.younger()));
    assertThat(onCalendar.getFirst().parentIds()).isEmpty();

    reset(mailer);
    assertThat(sync.run().created()).isZero();
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void acceptedEventTheSchoolMovesIsAskedAboutAgain() throws Exception {
    final Fixture f = family();
    schoolEvents.save(event("a", "Harriet's class trip", List.of(), "9:15am"));
    sync.run();
    final String trip = suggestionId(f, "Harriet's class trip");
    mockMvc.perform(post(path(f, trip, "accept")).with(user("alice")))
        .andExpect(status().isOk());

    schoolEvents.save(event("a", "Harriet's class trip", List.of(), "1:30pm"));
    reset(mailer);
    assertThat(sync.run().flagged()).isEqualTo(1);
    verify(mailer).needsApproval(anyList(), anyList(), anyMap());
    // Read again with nothing new: no second email.
    reset(mailer);
    sync.run();
    verify(mailer, never()).needsApproval(anyList(), anyList(), anyMap());

    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("bob")))
        .andExpect(jsonPath("$[0].review", is("changed")))
        .andExpect(jsonPath("$[0].proposed.startTime", is("13:30")));
    mockMvc.perform(post(path(f, trip, "review")).with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPLY\"}"))
        .andExpect(status().isOk());

    assertThat(coparent.findAll(CalendarEvent.class).getFirst().startTime()).isEqualTo("13:30");
  }

  @Test
  void childLinkedAfterTheirSiblingJoinsTheEventsAlreadyWaiting() throws Exception {
    final Fixture f = family();
    final Child younger = children.findById(new ObjectId(f.younger())).orElseThrow();
    children.save(withLink(younger, null));
    schoolEvents.save(event("a", "Winter Fair", List.of(), "2:00pm – 5:00pm"));
    sync.run();
    final String fair = suggestionId(f, "Winter Fair");
    assertThat(childIdsOf(fair)).containsExactly(new ObjectId(f.older()));

    children.save(withLink(younger, younger.schoolCalendar()));
    reset(mailer);
    sync.run();

    assertThat(childIdsOf(fair))
        .containsExactlyInAnyOrder(new ObjectId(f.older()), new ObjectId(f.younger()));
    // Still the one suggestion, already announced: nothing new to email.
    verify(mailer, never()).needsApproval(anyList(), anyList(), anyMap());
  }

  @Test
  void waitingTitleNotMadeOfTheSchoolsWordsIsPutRight() throws Exception {
    final Fixture f = family();
    schoolEvents.save(event("a", "Kilmorie Winter Fair", List.of(), "2:00pm – 5:00pm"));
    sync.run();
    final String fair = suggestionId(f, "Winter Fair");
    coparent.getCollection(V067CreateCoparentSchoolSuggestions.SUGGESTIONS).updateOne(
        new Document("_id", new ObjectId(fair)),
        new Document("$set", new Document("details.title", "Robin Example Winter Fair")
            .append("details.startTime", "14:05")));

    sync.run();

    final SchoolEventSuggestion after =
        coparent.findById(new ObjectId(fair), SchoolEventSuggestion.class);
    assertThat(after.details().title()).isEqualTo("Winter Fair");
    // Only the title goes; a time the model read is kept.
    assertThat(after.details().startTime()).isEqualTo("14:05");
  }

  private List<ObjectId> childIdsOf(final String suggestionId) {
    return coparent.findById(new ObjectId(suggestionId), SchoolEventSuggestion.class)
        .childIds();
  }

  private static Child withLink(final Child child, final Child.SchoolCalendarLink link) {
    return new Child(child.id(), child.familyId(), child.fullName(), child.dateOfBirth(),
        child.school(), child.medicalNotes(), child.avatarUrl(), child.deletedAt(),
        child.createdAt(), child.updatedAt(), link);
  }

  @Test
  void calendarWriteThatFailsLeavesTheReviewToBeAnsweredAgain() throws Exception {
    final Fixture f = family();
    schoolEvents.save(event("a", "Harriet's class trip", List.of(), "9:15am"));
    sync.run();
    final String trip = suggestionId(f, "Harriet's class trip");
    mockMvc.perform(post(path(f, trip, "accept")).with(user("alice")))
        .andExpect(status().isOk());
    schoolEvents.save(event("a", "Harriet's class trip", List.of(), "1:30pm"));
    sync.run();
    // The child leaves the family, so the calendar refuses the update.
    final Child child = children.findById(new ObjectId(f.younger())).orElseThrow();
    children.save(new Child(child.id(), child.familyId(), child.fullName(),
        child.dateOfBirth(), child.school(), child.medicalNotes(), child.avatarUrl(),
        Instant.now(), child.createdAt(), child.updatedAt(), child.schoolCalendar()));

    mockMvc.perform(post(path(f, trip, "review")).with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPLY\"}"))
        .andExpect(status().isBadRequest());

    final SchoolEventSuggestion after =
        coparent.findById(new ObjectId(trip), SchoolEventSuggestion.class);
    assertThat(after.review()).isEqualTo(SchoolEventSuggestion.Review.CHANGED);
    assertThat(after.proposed().startTime()).isEqualTo("13:30");
    assertThat(after.details().startTime()).isEqualTo("09:15");
    assertThat(coparent.findAll(CalendarEvent.class).getFirst().startTime()).isEqualTo("09:15");
  }

  @Test
  void acceptedEventTheSchoolNoLongerStatesCanBeTakenOff() throws Exception {
    final Fixture f = family();
    schoolEvents.saveAll(List.of(event("a", "Harriet's class trip", List.of(), null),
        event("b", "Winter Fair", List.of(), null)));
    sync.run();
    final String trip = suggestionId(f, "Harriet's class trip");
    mockMvc.perform(post(path(f, trip, "accept")).with(user("alice")))
        .andExpect(status().isOk());

    schoolEvents.deleteAll();
    reset(mailer);
    sync.run();

    // The pending one quietly goes; the accepted one is asked about.
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].review", is("removed")));
    mockMvc.perform(post(path(f, trip, "review")).with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPLY\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post(path(f, trip, "review")).with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REMOVE\"}"))
        .andExpect(status().isOk());

    assertThat(coparent.findAll(CalendarEvent.class).getFirst().deletedAt()).isNotNull();
  }

  @Test
  void theModelMergesTheSchoolsDuplicatesButOnlyOnOneDayForTheSameChildren() throws Exception {
    final Fixture f = family();
    schoolEvents.saveAll(List.of(
        event("a", "Harriet's class trip", List.of(), null),
        event("b", "Year 3 Local Area Walk – Harriet's class", List.of("Year 3"),
            "09.15 to 10.45am"),
        event("c", "Winter Fair", List.of(), null)));
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
        new AssistantMessage("""
            {"events":[{"members":["e1","e2","e3"],"title":"Everything","startTime":null,
             "endTime":null}]}""")))));

    runWith(true, "");

    // The model's merge of all three is refused (the fair is for both children); the rules
    // still keep three apart, so three suggestions.
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(3)));

    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
        new AssistantMessage("""
            {"events":[{"members":["e1","e2"],"title":"Local Area Walk","startTime":"09:15",
             "endTime":"10:45"}]}""")))));
    coparent.getCollection(V067CreateCoparentSchoolSuggestions.SUGGESTIONS)
        .deleteMany(new Document());
    runWith(true, "");
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[?(@.details.title == 'Local Area Walk')].sourceTitles[*]",
            hasSize(2)))
        .andExpect(jsonPath("$[?(@.details.title == 'Local Area Walk')].details.startTime")
            .value("09:15"));
  }

  @Test
  void onlyTheMailboxFamilyIsOfferedUnpublishedSchoolEmail() throws Exception {
    final Fixture f = family();
    schoolEvents.save(restricted("f", "Harriet's class letter about a trip", List.of()));

    sync.run();
    assertThat(coparent.count(new Query(),
        SchoolEventSuggestion.class)).isZero();

    runWith(false, f.familyId());
    mockMvc.perform(get("/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void linkingChildRecordsTheClassForThisSchoolYear() throws Exception {
    final Fixture f = family();
    mockMvc.perform(put("/api/coparent/children/{childId}/school-calendar", f.younger())
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"classTeacher\":\"Omar\",\"yearGroup\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schoolCalendar.classTeacher", is("Omar")))
        .andExpect(jsonPath("$.schoolCalendar.classIsCurrent", is(true)));
    mockMvc.perform(put("/api/coparent/children/{childId}/school-calendar", f.younger())
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"classTeacher\":\"<script>\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/api/coparent/children/{childId}/school-calendar", f.younger())
            .with(user("mallory"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"classTeacher\":\"Omar\"}"))
        .andExpect(status().isNotFound());
  }

  /** A sync of its own, with the model switched on or a mailbox family, run once. */
  private void runWith(final boolean ai, final String mailboxFamilyId) {
    final SchoolCalendarProperties settings =
        new SchoolCalendarProperties(true, ai, "test-model", 60, mailboxFamilyId);
    final SchoolSuggestionSync own = new SchoolSuggestionSync(schoolEvents, coparent,
        new SchoolEventMerger(chatModel, settings), mailer, parents, coparentProperties,
        settings, Clock.systemUTC());
    try {
      own.run();
    } finally {
      own.stop();
    }
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<List<SchoolEventSuggestion>> mailedItems() {
    return ArgumentCaptor.forClass(List.class);
  }

  private String suggestionId(final Fixture f, final String title) throws Exception {
    final MvcResult result = mockMvc.perform(get(
            "/api/coparent/families/{familyId}/school-suggestions", f.familyId())
            .with(user("alice")))
        .andReturn();
    final List<String> ids = JsonPath.read(result.getResponse().getContentAsString(),
        "$[?(@.details.title == \"" + title + "\")].id");
    return ids.getFirst();
  }

  private static String path(final Fixture f, final String suggestionId, final String action) {
    return "/api/coparent/families/%s/school-suggestions/%s/%s".formatted(f.familyId(),
        suggestionId, action);
  }

  private SchoolEvent event(final String id, final String title, final List<String> years,
      final String time) {
    return new SchoolEvent(id, title, day, day, time == null, SchoolEvent.EventType.OTHER,
        years, AcademicYear.of(day), SchoolSourceType.CALENDAR_FEED, "doc", Visibility.PUBLIC,
        null, null, time, null);
  }

  private SchoolEvent restricted(final String id, final String title, final List<String> years) {
    return new SchoolEvent(id, title, day, day, true, SchoolEvent.EventType.OTHER, years,
        AcademicYear.of(day), SchoolSourceType.EMAIL, "doc", Visibility.RESTRICTED, null, null,
        null, null);
  }

  /**
   * Two parents and two linked children: the younger in Year 3 in Harriet's class, the older in
   * Year 6, on {@link #day}.
   */
  private Fixture family() throws Exception {
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
    final String younger = child(familyId, "Robin Example", 3, "Harriet");
    final String older = child(familyId, "Sam Example", 6, null);
    return new Fixture(familyId, younger, older);
  }

  /** A child in a given year on {@link #day}, linked directly so no background run starts. */
  private String child(final String familyId, final String name, final int year,
      final String className) throws Exception {
    final int septemberYear = day.getMonthValue() >= 9 ? day.getYear() : day.getYear() - 1;
    final LocalDate born = LocalDate.of(septemberYear, Month.AUGUST, 31)
        .minusYears(4 + year).minusMonths(5);
    final MvcResult result = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"%s\",\"dateOfBirth\":\"%s\"}".formatted(name, born)))
        .andExpect(status().isCreated()).andReturn();
    final String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    final Child saved = children.findById(new ObjectId(id)).orElseThrow();
    children.save(new Child(saved.id(), saved.familyId(), saved.fullName(), saved.dateOfBirth(),
        saved.school(), saved.medicalNotes(), saved.avatarUrl(), null, saved.createdAt(),
        saved.updatedAt(),
        new Child.SchoolCalendarLink(className, null, AcademicYear.of(day))));
    return id;
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String younger, String older) {
  }
}
