package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.common.Image;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AdminProfileControllerTest extends AbstractIntegrationTest {

  @Autowired
  private AdminProfileRepository adminProfileRepository;

  @BeforeEach
  void setup() {
    adminProfileRepository.deleteAll();
  }

  @Test
  void getProfileReturnsProfile() throws Exception {
    adminProfileRepository.save(sampleProfile());

    mockMvc.perform(get("/api/admin/profile")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Simon Rowe"))
        .andExpect(jsonPath("$.title").value("Engineering Leader"))
        .andExpect(jsonPath("$.primaryEmail").value("simon@example.com"));
  }

  @Test
  void getProfileReturnsNotFoundWhenEmpty() throws Exception {
    mockMvc.perform(get("/api/admin/profile")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateProfileCreatesWhenNoneExists() throws Exception {
    mockMvc.perform(put("/api/admin/profile")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Simon Rowe",
                    "title": "Engineering Leader",
                    "headline": "Passionate about engineering",
                    "description": "I build great software.",
                    "location": "London",
                    "phoneNumber": "+441234567890",
                    "primaryEmail": "simon@example.com",
                    "secondaryEmail": null,
                    "profileImage": "/uploads/profile.jpg",
                    "sidebarImage": null,
                    "backgroundImage": null,
                    "mobileBackgroundImage": null
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Simon Rowe"))
        .andExpect(jsonPath("$.title").value("Engineering Leader"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void updateProfileUpdatesExisting() throws Exception {
    adminProfileRepository.save(sampleProfile());

    mockMvc.perform(put("/api/admin/profile")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Simon Rowe Updated",
                    "title": "Senior Engineering Leader",
                    "headline": "Building the future",
                    "description": "Updated description.",
                    "location": "London",
                    "phoneNumber": "+441234567890",
                    "primaryEmail": "updated@example.com",
                    "secondaryEmail": null,
                    "profileImage": "/uploads/profile.jpg",
                    "sidebarImage": null,
                    "backgroundImage": null,
                    "mobileBackgroundImage": null
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Simon Rowe Updated"))
        .andExpect(jsonPath("$.title").value("Senior Engineering Leader"))
        .andExpect(jsonPath("$.primaryEmail").value("updated@example.com"));
  }

  @Test
  void updateProfileValidatesRequiredFields() throws Exception {
    mockMvc.perform(put("/api/admin/profile")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Engineering Leader"
                }
                """))
        .andExpect(status().isBadRequest());
  }

  private static Profile sampleProfile() {
    final Instant now = Instant.parse("2026-02-21T10:00:00Z");
    return new Profile(
        null,
        "Simon Rowe",
        "Engineering Leader",
        "Passionate about engineering",
        "I build great software.",
        "London",
        "+441234567890",
        "simon@example.com",
        null,
        new Image("/uploads/profile.jpg", null, null, null, null, null),
        null,
        null,
        null,
        now,
        now
    );
  }
}
