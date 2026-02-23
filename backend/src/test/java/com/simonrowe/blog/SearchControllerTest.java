package com.simonrowe.blog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "management.health.kafka.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092"
})
@AutoConfigureMockMvc
@Testcontainers
class SearchControllerTest {

  private static final String ELASTICSEARCH_IMAGE =
      "docker.elastic.co/elasticsearch/elasticsearch:8.17.0";

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @Container
  static ElasticsearchContainer elasticsearch =
      new ElasticsearchContainer(ELASTICSEARCH_IMAGE)
          .withEnv("xpack.security.enabled", "false");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BlogSearchRepository blogSearchRepository;

  @Autowired
  private ElasticsearchOperations elasticsearchOperations;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
    registry.add("spring.elasticsearch.uris",
        () -> "http://" + elasticsearch.getHttpHostAddress());
  }

  @BeforeEach
  void setup() {
    blogSearchRepository.deleteAll();
    elasticsearchOperations.indexOps(BlogSearchDocument.class).refresh();
  }

  @Test
  void searchReturnsBlogsMatchingQuery() throws Exception {
    blogSearchRepository.saveAll(List.of(
        sampleDocument("b-1", "Spring Boot Tips", "Getting started with Spring Boot"),
        sampleDocument("b-2", "React Patterns", "Modern React component patterns")
    ));
    elasticsearchOperations.indexOps(BlogSearchDocument.class).refresh();

    mockMvc.perform(get("/api/search/blogs?q=Spring"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("b-1"))
        .andExpect(jsonPath("$[0].title").value("Spring Boot Tips"));
  }

  @Test
  void searchReturnsMultipleMatchingBlogs() throws Exception {
    blogSearchRepository.saveAll(List.of(
        sampleDocument("b-1", "Spring Boot Tips", "Getting started with Spring Boot"),
        sampleDocument("b-2", "Spring Security Guide", "Securing your Spring application"),
        sampleDocument("b-3", "React Patterns", "Modern React component patterns")
    ));
    elasticsearchOperations.indexOps(BlogSearchDocument.class).refresh();

    mockMvc.perform(get("/api/search/blogs?q=Spring"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void searchReturnsEmptyListWhenNoMatch() throws Exception {
    blogSearchRepository.save(
        sampleDocument("b-1", "Spring Boot Tips", "Getting started with Spring Boot")
    );
    elasticsearchOperations.indexOps(BlogSearchDocument.class).refresh();

    mockMvc.perform(get("/api/search/blogs?q=Python"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void searchReturnsBadRequestWhenQueryTooShort() throws Exception {
    mockMvc.perform(get("/api/search/blogs?q=a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void searchReturnsBadRequestWhenQueryMissing() throws Exception {
    mockMvc.perform(get("/api/search/blogs"))
        .andExpect(status().isBadRequest());
  }

  private static BlogSearchDocument sampleDocument(
      final String id, final String title, final String shortDescription) {
    return new BlogSearchDocument(
        id,
        title,
        shortDescription,
        "Content about " + title,
        List.of("java"),
        List.of("Spring Boot"),
        "/images/blogs/sample.jpg",
        Instant.parse("2024-06-01T10:00:00Z")
    );
  }
}
