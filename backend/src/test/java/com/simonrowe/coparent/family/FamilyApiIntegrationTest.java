package com.simonrowe.coparent.family;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.data.mongodb.core.MongoTemplate;

/** End-to-end family, profile, child and onboarding contract tests against real MongoDB. */
@TestPropertySource(properties = "coparent.enabled=true")
class FamilyApiIntegrationTest extends AbstractIntegrationTest {

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.EVENTS,
      V043CreateCoparentCollections.CATEGORIES,
      V043CreateCoparentCollections.SCHEDULE_CHANGES,
      V043CreateCoparentCollections.CONVERSATIONS,
      V043CreateCoparentCollections.AUDITS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @BeforeEach
  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection).drop());
  }

  @Test
  void createsInitialProfileFamilyChildAndCompletesOnboarding() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isNewUser", is(true)))
        .andExpect(jsonPath("$.profiles", hasSize(1)));

    final String familyId = createFamily("alice", "Alice Example", "Example Family");
    final MvcResult child = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"fullName":"Robin","dateOfBirth":"2018-04-03","school":"Kilmorie",
                 "medicalNotes":"Private"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.dateOfBirth", is("2018-04-03")))
        .andReturn();
    final String childId = JsonPath.read(child.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(patch("/api/coparent/children/{childId}", childId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"school\":\"New School\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.school", is("New School")));

    mockMvc.perform(post("/api/coparent/onboarding/{familyId}/complete-step", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"step\":\"review\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isComplete", is(true)))
        .andExpect(jsonPath("$.currentStep", is("complete")));

    mockMvc.perform(get("/api/coparent/families").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].childIds", contains(childId)));
  }

  @Test
  void concealsAnotherFamiliesRecords() throws Exception {
    final String familyId = createFamily("alice", "Alice", "Alice Family");

    mockMvc.perform(get("/api/coparent/families/{familyId}", familyId).with(user("mallory")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/coparent/families/{familyId}/children", familyId)
            .with(user("mallory")))
        .andExpect(status().isNotFound());
  }

  @Test
  void softDeletedChildDisappearsFromReadsAndFamilyReferences() throws Exception {
    final String familyId = createFamily("alice", "Alice", "Alice Family");
    final MvcResult created = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    final String childId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(delete("/api/coparent/children/{childId}", childId).with(user("alice")))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/coparent/children/{childId}", childId).with(user("alice")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/coparent/families/{familyId}", familyId).with(user("alice")))
        .andExpect(jsonPath("$.childIds", hasSize(0)));
  }

  @Test
  void updatesProfileFamilyAndOnboardingState() throws Exception {
    final String familyId = createFamily("alice", "Alice", "Alice Family");

    mockMvc.perform(patch("/api/coparent/me")
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Alice Updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName", is("Alice Updated")));

    mockMvc.perform(patch("/api/coparent/families/{familyId}", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Updated Family\",\"timeZone\":\"Europe/Paris\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", is("Updated Family")))
        .andExpect(jsonPath("$.timeZone", is("Europe/Paris")));

    mockMvc.perform(patch("/api/coparent/onboarding/{familyId}", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentStep":"child","completedSteps":["account","family","account"],
                 "isComplete":false}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentStep", is("child")))
        .andExpect(jsonPath("$.completedSteps", contains("account", "family")))
        .andExpect(jsonPath("$.isComplete", is(false)));
  }

  private String createFamily(
      final String subject,
      final String fullName,
      final String familyName) throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user(subject))).andExpect(status().isOk());
    final MvcResult result = mockMvc.perform(post("/api/coparent/families")
            .with(user(subject))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"%s","timeZone":"Europe/London","fullName":"%s"}
                """.formatted(familyName, fullName)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }
}
