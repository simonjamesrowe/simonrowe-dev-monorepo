package com.simonrowe.coparent.invitation;

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
import java.time.Instant;
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

/** Invitation lifecycle contract tests, including recipient and one-time-token rules. */
@TestPropertySource(properties = "coparent.enabled=true")
class InvitationApiIntegrationTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
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
  void createsRedactedInvitationAndRejectsDuplicate() throws Exception {
    final String familyId = createFamily();

    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice", "alice@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email", is("bob@example.com")))
        .andExpect(jsonPath("$.token").doesNotExist());

    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice", "alice@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice", "alice@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].token").doesNotExist());
  }

  @Test
  void intendedRecipientAcceptsOnceAndJoinsFamily() throws Exception {
    final String familyId = createFamily();
    createInvitation(familyId, "bob@example.com");
    final Invitation invitation = invitations.findAll().getFirst();

    mockMvc.perform(post("/api/coparent/invitations/accept")
            .with(user("bob", "bob@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.family.id", is(familyId)))
        .andExpect(jsonPath("$.invitation.status", is("accepted")))
        .andExpect(jsonPath("$.invitation.token").doesNotExist());

    mockMvc.perform(get("/api/coparent/families/{familyId}", familyId)
            .with(user("bob", "bob@example.com")))
        .andExpect(status().isOk());
    assertThatOneParentWasAdded(familyId);
  }

  @Test
  void wrongRecipientAndExpiredInvitationCreateNoMembership() throws Exception {
    final String familyId = createFamily();
    createInvitation(familyId, "bob@example.com");
    Invitation invitation = invitations.findAll().getFirst();

    mockMvc.perform(post("/api/coparent/invitations/accept")
            .with(user("mallory", "mallory@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isForbidden());

    invitation = invitations.save(new Invitation(invitation.id(), invitation.familyId(),
        invitation.email(), invitation.role(), invitation.status(), invitation.token(),
        invitation.sentAt(), Instant.now().minusSeconds(1), null, null, null, null,
        invitation.createdAt(), Instant.now()));
    mockMvc.perform(post("/api/coparent/invitations/accept")
            .with(user("bob", "bob@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/coparent/families/{familyId}", familyId)
            .with(user("bob", "bob@example.com")))
        .andExpect(status().isNotFound());
  }

  @Test
  void cancelAndResendRespectTerminalStates() throws Exception {
    final String familyId = createFamily();
    final String invitationId = createInvitation(familyId, "bob@example.com");

    mockMvc.perform(post("/api/coparent/invitations/{id}/cancel", invitationId)
            .with(user("alice", "alice@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("canceled")));
    mockMvc.perform(post("/api/coparent/invitations/{id}/resend", invitationId)
            .with(user("alice", "alice@example.com")))
        .andExpect(status().isBadRequest());
  }

  private String createFamily() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice", "alice@example.com")))
        .andExpect(status().isOk());
    final MvcResult result = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice", "alice@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice"}
                """))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String createInvitation(final String familyId, final String email) throws Exception {
    final MvcResult result = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice", "alice@example.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private void assertThatOneParentWasAdded(final String familyId) throws Exception {
    mockMvc.perform(get("/api/coparent/families/{familyId}/parents", familyId)
            .with(user("alice", "alice@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)));
  }

  private JwtRequestPostProcessor user(final String subject, final String email) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", email));
  }
}
