package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.blog.BlogSearchRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "management.health.elasticsearch.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.elasticsearch.uris=http://localhost:9200",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.auth0.com/",
    "spring.security.oauth2.resourceserver.jwt.audiences=https://test-api"
})
@AutoConfigureMockMvc
@Testcontainers
class AdminSkillGroupControllerTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AdminSkillGroupRepository adminSkillGroupRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    adminSkillGroupRepository.deleteAll();
  }

  @Test
  void listSkillGroupsReturnsPaginatedResults() throws Exception {
    adminSkillGroupRepository.saveAll(List.of(
        sampleSkillGroup("g-1", "JVM Languages", 0),
        sampleSkillGroup("g-2", "Databases", 1)
    ));

    mockMvc.perform(get("/api/admin/skill-groups")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void createSkillGroupReturnsCreated() throws Exception {
    String body = """
        {
            "name": "Cloud Platforms",
            "rating": 7.5,
            "description": "Cloud and infrastructure technologies",
            "order": 0,
            "skills": []
        }
        """;

    mockMvc.perform(post("/api/admin/skill-groups")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Cloud Platforms"))
        .andExpect(jsonPath("$.rating").value(7.5))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void createSkillGroupValidatesRequiredFields() throws Exception {
    String body = """
        {
            "rating": 7.0,
            "description": "Some description"
        }
        """;

    mockMvc.perform(post("/api/admin/skill-groups")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getSkillGroupByIdReturnsSkillGroup() throws Exception {
    adminSkillGroupRepository.save(sampleSkillGroup("g-1", "JVM Languages", 0));

    mockMvc.perform(get("/api/admin/skill-groups/g-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("g-1"))
        .andExpect(jsonPath("$.name").value("JVM Languages"));
  }

  @Test
  void updateSkillGroupReturnsUpdated() throws Exception {
    adminSkillGroupRepository.save(sampleSkillGroup("g-1", "JVM Languages", 0));

    String body = """
        {
            "name": "JVM Languages (Updated)",
            "rating": 9.0,
            "description": "Updated description",
            "order": 1,
            "skills": ["s-1", "s-2"]
        }
        """;

    mockMvc.perform(put("/api/admin/skill-groups/g-1")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("g-1"))
        .andExpect(jsonPath("$.name").value("JVM Languages (Updated)"))
        .andExpect(jsonPath("$.rating").value(9.0));
  }

  @Test
  void deleteSkillGroupReturnsNoContent() throws Exception {
    adminSkillGroupRepository.save(sampleSkillGroup("g-1", "JVM Languages", 0));

    mockMvc.perform(delete("/api/admin/skill-groups/g-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void reorderSkillGroupsUpdatesOrder() throws Exception {
    adminSkillGroupRepository.saveAll(List.of(
        sampleSkillGroup("g-1", "JVM Languages", 0),
        sampleSkillGroup("g-2", "Databases", 1),
        sampleSkillGroup("g-3", "Cloud Platforms", 2)
    ));

    String body = """
        {
            "orderedIds": ["g-3", "g-1", "g-2"]
        }
        """;

    mockMvc.perform(patch("/api/admin/skill-groups/reorder")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/skill-groups")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Cloud Platforms"))
        .andExpect(jsonPath("$.content[0].order").value(0))
        .andExpect(jsonPath("$.content[1].name").value("JVM Languages"))
        .andExpect(jsonPath("$.content[1].order").value(1))
        .andExpect(jsonPath("$.content[2].name").value("Databases"))
        .andExpect(jsonPath("$.content[2].order").value(2));
  }

  private static SkillGroup sampleSkillGroup(
      final String id,
      final String name,
      final int order
  ) {
    Instant now = Instant.parse("2024-06-01T10:00:00Z");
    return new SkillGroup(
        id,
        name,
        8.0,
        "Description of " + name,
        null,
        order,
        List.of(),
        now,
        now,
        null
    );
  }
}
