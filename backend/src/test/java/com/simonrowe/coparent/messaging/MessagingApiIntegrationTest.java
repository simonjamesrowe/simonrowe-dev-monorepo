package com.simonrowe.coparent.messaging;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.model.Parent;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Messaging, unread-state and formal permission contracts against real MongoDB. */
@TestPropertySource(properties = "coparent.enabled=true")
class MessagingApiIntegrationTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.CONVERSATIONS,
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
  void exchangesMessagesAndMaintainsCallerRelativeUnreadState() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/conversations/message", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"School pickup\",\"message\":\"Can you collect?\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.unreadCount", is(0)))
        .andExpect(jsonPath("$.messages[0].deliveryStatus", is("delivered")))
        .andReturn();
    final String conversationId = JsonPath.read(
        created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(get("/api/coparent/families/{familyId}/conversations", fixture.familyId())
            .with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unreadCount", is(1)))
        .andExpect(jsonPath("$[0].messages[0].deliveryStatus", is("delivered")));

    mockMvc.perform(post("/api/coparent/conversations/{id}/mark-read", conversationId)
            .with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[0].deliveryStatus", is("read")));

    mockMvc.perform(get("/api/coparent/families/{familyId}/conversations", fixture.familyId())
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].messages[0].deliveryStatus", is("read")));

    mockMvc.perform(post("/api/coparent/conversations/{id}/messages", conversationId)
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"Yes, I can.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messages[1].senderId").exists())
        .andExpect(jsonPath("$.messages[1].deliveryStatus", is("delivered")));
    mockMvc.perform(get("/api/coparent/families/{familyId}/conversations", fixture.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$[0].unreadCount", is(1)));
    mockMvc.perform(post("/api/coparent/conversations/{id}/mark-read", conversationId)
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unreadCount", is(0)))
        .andExpect(jsonPath("$.messages[1].deliveryStatus", is("read")));

    mongoTemplate.updateFirst(Query.query(Criteria.where("auth0Id").is("auth0|bob")),
        Update.update("status", "inactive"), Parent.class);
    mockMvc.perform(post("/api/coparent/conversations/{id}/messages", conversationId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"Are you still there?\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messages[2].deliveryStatus", is("sent")));

    mockMvc.perform(post("/api/coparent/conversations/{id}/messages", conversationId)
            .with(user("mallory"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"Intrusion\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void onlyOtherParentCanResolvePendingPermission() throws Exception {
    final Fixture fixture = familyWithTwoParentsAndChild();
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/conversations/permission", fixture.familyId())
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subject":"School trip","type":"travel","childId":"%s",
                 "description":"May Robin attend?"}
                """.formatted(fixture.childId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.permissionRequest.status", is("pending")))
        .andReturn();
    final String permissionId = JsonPath.read(
        created.getResponse().getContentAsString(), "$.permissionRequest.id");

    mockMvc.perform(post("/api/coparent/permissions/{id}/approve", permissionId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/coparent/permissions/{id}/approve", permissionId)
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"response\":\"Approved\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissionRequest.status", is("approved")))
        .andExpect(jsonPath("$.permissionRequest.response", is("Approved")));
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

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId) {
  }
}
