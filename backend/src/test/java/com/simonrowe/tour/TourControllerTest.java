package com.simonrowe.tour;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.blog.BlogSearchRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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
    "spring.elasticsearch.uris=http://localhost:9200"
})
@AutoConfigureMockMvc
@Testcontainers
class TourControllerTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private TourStepRepository tourStepRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    tourStepRepository.deleteAll();
  }

  @Test
  void getStepsReturnsOrderedSteps() throws Exception {
    tourStepRepository.saveAll(List.of(
        sampleStep("s-1", 3, ".contact", "Contact"),
        sampleStep("s-2", 1, ".banner", "Welcome"),
        sampleStep("s-3", 2, ".about", "About")
    ));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].order").value(1))
        .andExpect(jsonPath("$[0].title").value("Welcome"))
        .andExpect(jsonPath("$[0].targetSelector").value(".banner"))
        .andExpect(jsonPath("$[1].order").value(2))
        .andExpect(jsonPath("$[1].title").value("About"))
        .andExpect(jsonPath("$[2].order").value(3))
        .andExpect(jsonPath("$[2].title").value("Contact"));
  }

  @Test
  void getStepsReturnsEmptyListWhenNoSteps() throws Exception {
    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getStepsReturnsAllFields() throws Exception {
    tourStepRepository.save(new TourStep(
        "s-1", 1, ".homepage-banner", "Welcome",
        "/images/tour/welcome.png",
        "This is the **homepage banner**.",
        "bottom"
    ));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("s-1"))
        .andExpect(jsonPath("$[0].order").value(1))
        .andExpect(jsonPath("$[0].targetSelector").value(".homepage-banner"))
        .andExpect(jsonPath("$[0].title").value("Welcome"))
        .andExpect(jsonPath("$[0].titleImage").value("/images/tour/welcome.png"))
        .andExpect(jsonPath("$[0].description").value("This is the **homepage banner**."))
        .andExpect(jsonPath("$[0].position").value("bottom"));
  }

  @Test
  void getStepsReturnsNullTitleImage() throws Exception {
    tourStepRepository.save(sampleStep("s-1", 1, ".banner", "Welcome"));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].titleImage").doesNotExist());
  }

  private static TourStep sampleStep(final String id, final int order,
      final String selector, final String title) {
    return new TourStep(id, order, selector, title, null,
        "Description for " + title, "bottom");
  }
}
