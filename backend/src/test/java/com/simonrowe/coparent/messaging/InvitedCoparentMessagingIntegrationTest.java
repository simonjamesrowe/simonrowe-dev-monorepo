package com.simonrowe.coparent.messaging;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V053ReserveInvitedCoparents;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Messages and permission requests before the co-parent accepts their invitation: addressed to
 * the parent the invitation reserved, waiting for them, and theirs, unread, once they join.
 */
@TestPropertySource(properties = "coparent.enabled=true")
class InvitedCoparentMessagingIntegrationTest extends AbstractIntegrationTest {

  private static final String CONVERSATIONS = "/api/coparent/families/{familyId}/conversations";
  private static final String PARENTS = "/api/coparent/families/{familyId}/parents";

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V043CreateCoparentCollections.CONVERSATIONS);

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
    V053ReserveInvitedCoparents.createIndexes(mongoTemplate);
  }

  @Test
  void threadsStartedBeforeTheInviteIsAcceptedAreWaitingForTheCoparentWhenTheyJoin()
      throws Exception {
    final Fixture f = familyWithInvite("Rhian");

    final String unaddressed = startMessage(f, null)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.participants.parent2.id", is(f.invitedId())))
        .andExpect(jsonPath("$.participants.parent2.name", is("Rhian")))
        .andExpect(jsonPath("$.messages[0].deliveryStatus", is("sent")))
        .andReturn().getResponse().getContentAsString();
    startMessage(f, f.invitedId()).andExpect(status().isCreated());
    final MvcResult permission = mockMvc.perform(post(CONVERSATIONS + "/permission",
            f.familyId()).with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subject":"Cornwall","type":"travel","childId":"%s","description":"Trip"}
                """.formatted(f.childId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.participants.parent2.id", is(f.invitedId())))
        .andReturn();
    final String conversationId = JsonPath.read(unaddressed, "$.id");
    final String permissionId = JsonPath.read(
        permission.getResponse().getContentAsString(), "$.permissionRequest.id");
    mockMvc.perform(post("/api/coparent/conversations/{id}/messages", conversationId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"Also, half term?\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messages[1].deliveryStatus", is("sent")));

    accept("rhian");

    mockMvc.perform(get(CONVERSATIONS, f.familyId()).with(user("rhian")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[?(@.id=='" + conversationId + "')].unreadCount").value(2));
    mockMvc.perform(post("/api/coparent/conversations/{id}/messages", conversationId)
            .with(user("rhian")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"Happy to\"}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/coparent/permissions/{id}/approve", permissionId)
            .with(user("rhian")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionRequest.status", is("approved")));
    startMessage(f, null).andExpect(status().isCreated())
        .andExpect(jsonPath("$.messages[0].deliveryStatus", is("delivered")));
  }

  @Test
  void thirdPersonStillInvitedIsNeverWrittenTo() throws Exception {
    final Fixture f = familyWithInvite("Rhian");
    accept("rhian");
    invite(f.familyId(), "carol@example.com", "Carol");
    final String carolId = parentId(f.familyId(), "invited");

    startMessage(f, carolId).andExpect(status().isBadRequest());
    startMessage(f, null).andExpect(status().isCreated())
        .andExpect(jsonPath("$.participants.parent2.id", is(f.invitedId())));
  }

  @Test
  void cancellingKeepsTheThreadAndItsNameButStopsNewOnes() throws Exception {
    final Fixture f = familyWithInvite("Rhian");
    startMessage(f, null).andExpect(status().isCreated());

    mockMvc.perform(post("/api/coparent/invitations/{id}/cancel", invitation().id().toHexString())
            .with(user("alice")))
        .andExpect(status().isOk());

    mockMvc.perform(get(CONVERSATIONS, f.familyId()).with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].participants.parent2.name", is("Rhian")));
    startMessage(f, null).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("Invite your co-parent")));
    startMessage(f, f.invitedId()).andExpect(status().isBadRequest());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private Fixture familyWithInvite(final String name) throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult familyResult = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String familyId = JsonPath.read(familyResult.getResponse().getContentAsString(), "$.id");
    invite(familyId, "rhian@example.com", name);
    final MvcResult childResult = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    return new Fixture(familyId,
        JsonPath.read(childResult.getResponse().getContentAsString(), "$.id"),
        parentId(familyId, "invited"));
  }

  private String parentId(final String familyId, final String status) throws Exception {
    final MvcResult listed = mockMvc.perform(get(PARENTS, familyId)
            .param("includeInvited", "true").with(user("alice")))
        .andExpect(status().isOk()).andReturn();
    return JsonPath.<List<String>>read(listed.getResponse().getContentAsString(),
        "$[?(@.status=='" + status + "')].id").getFirst();
  }

  private void invite(final String familyId, final String email, final String name)
      throws Exception {
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"role\":\"co-parent\",\"name\":\"%s\"}"
                .formatted(email, name)))
        .andExpect(status().isCreated());
  }

  private Invitation invitation() {
    return invitations.findAll().stream()
        .filter(candidate -> "rhian@example.com".equals(candidate.email()))
        .filter(candidate -> !"canceled".equals(candidate.status()))
        .findFirst().orElseThrow();
  }

  private void accept(final String who) throws Exception {
    mockMvc.perform(post("/api/coparent/invitations/accept").with(user(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation().token() + "\"}"))
        .andExpect(status().isOk());
  }

  private ResultActions startMessage(final Fixture f, final String recipientId)
      throws Exception {
    final String recipient = recipientId == null ? "" : "\"recipientId\":\"%s\","
        .formatted(recipientId);
    return mockMvc.perform(post(CONVERSATIONS + "/message", f.familyId())
        .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
        .content("{%s\"subject\":\"Pickup\",\"message\":\"Can you collect?\"}"
            .formatted(recipient)));
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId, String invitedId) {
  }
}
