package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.SharedMongoContainer;
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

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "management.health.elasticsearch.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.elasticsearch.uris=http://localhost:9200",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.auth0.com/",
    "spring.security.oauth2.resourceserver.jwt.audiences=https://test-api"
})
@AutoConfigureMockMvc
class AdminSkillControllerTest {

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AdminSkillRepository adminSkillRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    adminSkillRepository.deleteAll();
  }

  @Test
  void listSkillsReturnsPaginatedResults() throws Exception {
    adminSkillRepository.saveAll(List.of(
        sampleSkill("s-1", "Java", 0),
        sampleSkill("s-2", "Spring Boot", 1)
    ));

    mockMvc.perform(get("/api/admin/skills")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void createSkillReturnsCreated() throws Exception {
    String body = """
        {
            "name": "Kotlin",
            "rating": 8.5,
            "description": "Modern JVM language",
            "order": 0
        }
        """;

    mockMvc.perform(post("/api/admin/skills")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Kotlin"))
        .andExpect(jsonPath("$.rating").value(8.5))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void createSkillValidatesRequiredFields() throws Exception {
    String body = """
        {
            "rating": 7.0,
            "description": "Some description"
        }
        """;

    mockMvc.perform(post("/api/admin/skills")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getSkillByIdReturnsSkill() throws Exception {
    adminSkillRepository.save(sampleSkill("s-1", "Java", 0));

    mockMvc.perform(get("/api/admin/skills/s-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("s-1"))
        .andExpect(jsonPath("$.name").value("Java"));
  }

  @Test
  void updateSkillReturnsUpdated() throws Exception {
    adminSkillRepository.save(sampleSkill("s-1", "Java", 0));

    String body = """
        {
            "name": "Java (Updated)",
            "rating": 9.5,
            "description": "Updated description",
            "order": 1
        }
        """;

    mockMvc.perform(put("/api/admin/skills/s-1")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("s-1"))
        .andExpect(jsonPath("$.name").value("Java (Updated)"))
        .andExpect(jsonPath("$.rating").value(9.5));
  }

  @Test
  void deleteSkillReturnsNoContent() throws Exception {
    adminSkillRepository.save(sampleSkill("s-1", "Java", 0));

    mockMvc.perform(delete("/api/admin/skills/s-1")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void reorderSkillsUpdatesOrder() throws Exception {
    adminSkillRepository.saveAll(List.of(
        sampleSkill("s-1", "Java", 0),
        sampleSkill("s-2", "Kotlin", 1),
        sampleSkill("s-3", "Python", 2)
    ));

    String body = """
        {
            "orderedIds": ["s-3", "s-1", "s-2"]
        }
        """;

    mockMvc.perform(patch("/api/admin/skills/reorder")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/skills")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Python"))
        .andExpect(jsonPath("$.content[0].order").value(0))
        .andExpect(jsonPath("$.content[1].name").value("Java"))
        .andExpect(jsonPath("$.content[1].order").value(1))
        .andExpect(jsonPath("$.content[2].name").value("Kotlin"))
        .andExpect(jsonPath("$.content[2].order").value(2));
  }

  private static Skill sampleSkill(final String id, final String name, final int order) {
    Instant now = Instant.parse("2024-06-01T10:00:00Z");
    return new Skill(
        id,
        name,
        8.0,
        "Description of " + name,
        null,
        order,
        now,
        now,
        null
    );
  }
}
