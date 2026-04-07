package com.simonrowe.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProfileControllerTest extends AbstractIntegrationTest {

  @Autowired
  private ProfileRepository profileRepository;

  @Autowired
  private SocialMediaLinkRepository socialMediaLinkRepository;

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
