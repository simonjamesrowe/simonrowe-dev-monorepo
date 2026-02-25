package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AdminSocialMediaControllerTest {

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private ElasticsearchOperations elasticsearchOperations;

  @MockitoBean
  private com.simonrowe.blog.BlogSearchRepository blogSearchRepository;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AdminSocialMediaRepository adminSocialMediaRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    SharedMongoContainer.configureProperties(registry);
  }

  @BeforeEach
  void setup() {
    adminSocialMediaRepository.deleteAll();
  }

  @Test
  void listSocialMediaReturnsAll() throws Exception {
    adminSocialMediaRepository.saveAll(List.of(
        sampleSocialMediaLink("github", "https://github.com/simonrowe", "GitHub"),
        sampleSocialMediaLink("linkedin", "https://linkedin.com/in/simon", "LinkedIn")
    ));

    mockMvc.perform(get("/api/admin/social-media")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void createSocialMediaReturnsCreated() throws Exception {
    mockMvc.perform(post("/api/admin/social-media")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "type": "github",
                    "link": "https://github.com/simonrowe",
                    "name": "GitHub",
                    "includeOnResume": true
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.type").value("github"))
        .andExpect(jsonPath("$.link").value("https://github.com/simonrowe"))
        .andExpect(jsonPath("$.name").value("GitHub"))
        .andExpect(jsonPath("$.includeOnResume").value(true));
  }

  @Test
  void createSocialMediaValidatesRequiredFields() throws Exception {
    mockMvc.perform(post("/api/admin/social-media")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "link": "https://github.com/simonrowe",
                    "name": "GitHub"
                }
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getSocialMediaByIdReturnsSocialMedia() throws Exception {
    final SocialMediaLink saved = adminSocialMediaRepository.save(
        sampleSocialMediaLink("github", "https://github.com/simonrowe", "GitHub")
    );

    mockMvc.perform(get("/api/admin/social-media/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.type").value("github"))
        .andExpect(jsonPath("$.link").value("https://github.com/simonrowe"));
  }

  @Test
  void updateSocialMediaReturnsUpdated() throws Exception {
    final SocialMediaLink saved = adminSocialMediaRepository.save(
        sampleSocialMediaLink("github", "https://github.com/simonrowe", "GitHub")
    );

    mockMvc.perform(put("/api/admin/social-media/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "type": "github",
                    "link": "https://github.com/simonrowe-updated",
                    "name": "GitHub Updated",
                    "includeOnResume": false
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.link").value("https://github.com/simonrowe-updated"))
        .andExpect(jsonPath("$.name").value("GitHub Updated"))
        .andExpect(jsonPath("$.includeOnResume").value(false));
  }

  @Test
  void deleteSocialMediaReturnsNoContent() throws Exception {
    final SocialMediaLink saved = adminSocialMediaRepository.save(
        sampleSocialMediaLink("github", "https://github.com/simonrowe", "GitHub")
    );

    mockMvc.perform(delete("/api/admin/social-media/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  private static SocialMediaLink sampleSocialMediaLink(
      final String type,
      final String link,
      final String name
  ) {
    final Instant now = Instant.parse("2026-02-21T10:00:00Z");
    return new SocialMediaLink(null, type, link, name, false, now, now, null);
  }
}
