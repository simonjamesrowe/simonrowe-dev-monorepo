package com.simonrowe.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.blog.BlogSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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
class SecurityConfigTest {

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

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @Test
  void publicEndpointIsAccessibleWithoutAuth() throws Exception {
    mockMvc.perform(get("/api/blogs"))
        .andExpect(status().isOk());
  }

  @Test
  void adminEndpointRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/admin/blogs"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminEndpointIsAccessibleWithJwt() throws Exception {
    mockMvc.perform(get("/api/admin/blogs")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk());
  }
}
