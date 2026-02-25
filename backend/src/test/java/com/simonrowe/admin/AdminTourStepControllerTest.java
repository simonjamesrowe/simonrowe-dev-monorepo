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
class AdminTourStepControllerTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private com.simonrowe.blog.BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AdminTourStepRepository adminTourStepRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    adminTourStepRepository.deleteAll();
  }

  @Test
  void listTourStepsReturnsAllOrdered() throws Exception {
    adminTourStepRepository.saveAll(List.of(
        sampleTourStep("Welcome", ".banner", 3),
        sampleTourStep("About", ".about", 1),
        sampleTourStep("Contact", ".contact", 2)
    ));

    mockMvc.perform(get("/api/admin/tour-steps")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].order").value(1))
        .andExpect(jsonPath("$[0].title").value("About"))
        .andExpect(jsonPath("$[1].order").value(2))
        .andExpect(jsonPath("$[1].title").value("Contact"))
        .andExpect(jsonPath("$[2].order").value(3))
        .andExpect(jsonPath("$[2].title").value("Welcome"));
  }

  @Test
  void createTourStepReturnsCreated() throws Exception {
    mockMvc.perform(post("/api/admin/tour-steps")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Welcome",
                    "selector": ".banner",
                    "description": "This is the welcome banner.",
                    "titleImage": "/images/tour/welcome.png",
                    "position": "bottom",
                    "order": 1
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.title").value("Welcome"))
        .andExpect(jsonPath("$.selector").value(".banner"))
        .andExpect(jsonPath("$.description").value("This is the welcome banner."))
        .andExpect(jsonPath("$.position").value("bottom"))
        .andExpect(jsonPath("$.order").value(1));
  }

  @Test
  void createTourStepValidatesRequiredFields() throws Exception {
    mockMvc.perform(post("/api/admin/tour-steps")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "selector": ".banner",
                    "description": "Missing title."
                }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getTourStepByIdReturnsTourStep() throws Exception {
    final TourStep saved = adminTourStepRepository.save(
        sampleTourStep("Welcome", ".banner", 1)
    );

    mockMvc.perform(get("/api/admin/tour-steps/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.title").value("Welcome"))
        .andExpect(jsonPath("$.selector").value(".banner"));
  }

  @Test
  void updateTourStepReturnsUpdated() throws Exception {
    final TourStep saved = adminTourStepRepository.save(
        sampleTourStep("Welcome", ".banner", 1)
    );

    mockMvc.perform(put("/api/admin/tour-steps/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Welcome Updated",
                    "selector": ".banner-updated",
                    "description": "Updated description.",
                    "titleImage": null,
                    "position": "top",
                    "order": 2
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.title").value("Welcome Updated"))
        .andExpect(jsonPath("$.selector").value(".banner-updated"))
        .andExpect(jsonPath("$.position").value("top"))
        .andExpect(jsonPath("$.order").value(2));
  }

  @Test
  void deleteTourStepReturnsNoContent() throws Exception {
    final TourStep saved = adminTourStepRepository.save(
        sampleTourStep("Welcome", ".banner", 1)
    );

    mockMvc.perform(delete("/api/admin/tour-steps/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void reorderTourStepsUpdatesOrder() throws Exception {
    final List<TourStep> saved = adminTourStepRepository.saveAll(List.of(
        sampleTourStep("First", ".first", 1),
        sampleTourStep("Second", ".second", 2),
        sampleTourStep("Third", ".third", 3)
    ));

    final String firstId = saved.get(0).id();
    final String secondId = saved.get(1).id();
    final String thirdId = saved.get(2).id();

    mockMvc.perform(patch("/api/admin/tour-steps/reorder")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "orderedIds": ["%s", "%s", "%s"]
                }
                """.formatted(thirdId, firstId, secondId)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/tour-steps")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(thirdId))
        .andExpect(jsonPath("$[0].order").value(0))
        .andExpect(jsonPath("$[1].id").value(firstId))
        .andExpect(jsonPath("$[1].order").value(1))
        .andExpect(jsonPath("$[2].id").value(secondId))
        .andExpect(jsonPath("$[2].order").value(2));
  }

  private static TourStep sampleTourStep(
      final String title,
      final String selector,
      final int order
  ) {
    final Instant now = Instant.parse("2026-02-21T10:00:00Z");
    return new TourStep(
        null,
        title,
        selector,
        "Description for " + title,
        null,
        "bottom",
        order,
        now,
        now,
        null
    );
  }
}
