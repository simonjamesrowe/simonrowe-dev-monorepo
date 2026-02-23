package com.simonrowe.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class ProfileControllerTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProfileRepository profileRepository;

  @Autowired
  private SocialMediaLinkRepository socialMediaLinkRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    socialMediaLinkRepository.deleteAll();
    profileRepository.deleteAll();
  }

  @Test
  void getProfileReturnsProfileWhenDataExists() throws Exception {
    profileRepository.save(sampleProfile());
    socialMediaLinkRepository.saveAll(List.of(
        sampleSocialMediaLink("github", "GitHub", "https://github.com/simonrowe", true),
        sampleSocialMediaLink("linkedin", "LinkedIn", "https://linkedin.com/in/simon", false)
    ));

    mockMvc.perform(get("/api/profile"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Simon Rowe"))
        .andExpect(jsonPath("$.firstName").value("Simon"))
        .andExpect(jsonPath("$.profileImage.url").value("/uploads/profile.jpg"))
        .andExpect(jsonPath("$.socialMediaLinks.length()").value(2))
        .andExpect(jsonPath("$.socialMediaLinks[0].type").value("github"))
        .andExpect(jsonPath("$.socialMediaLinks[0].url").value("https://github.com/simonrowe"));
  }

  @Test
  void getProfileReturnsNotFoundWhenProfileIsMissing() throws Exception {
    mockMvc.perform(get("/api/profile"))
        .andExpect(status().isNotFound());
  }

  private static Profile sampleProfile() {
    Instant now = Instant.parse("2026-02-21T10:00:00Z");
    Image image = new Image(
        "/uploads/profile.jpg",
        "profile.jpg",
        400,
        400,
        "image/jpeg",
        null
    );

    return new Profile(
        "profile-1",
        "Simon Rowe",
        "Simon",
        "Rowe",
        "Engineering Leader",
        "PASSIONATE ABOUT AI NATIVE DEV",
        "I am driven to deliver value.",
        image,
        image,
        image,
        image,
        "London",
        "+447909083522",
        "simon.rowe@gmail.com",
        "",
        "/api/resume",
        now,
        now
    );
  }

  private static SocialMediaLink sampleSocialMediaLink(
      String type,
      String name,
      String link,
      boolean includeOnResume
  ) {
    Instant now = Instant.parse("2026-02-21T10:00:00Z");

    return new SocialMediaLink(
        null,
        type,
        name,
        link,
        includeOnResume,
        now,
        now
    );
  }
}
