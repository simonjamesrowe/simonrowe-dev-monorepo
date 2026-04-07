package com.simonrowe.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AdminTagControllerTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTagRepository adminTagRepository;

  @BeforeEach
  void setup() {
    adminTagRepository.deleteAll();
  }

  @Test
  void listTagsReturnsAll() throws Exception {
    adminTagRepository.saveAll(List.of(
        sampleTag("Java"),
        sampleTag("Python")
    ));

    mockMvc.perform(get("/api/admin/tags")
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void createTagReturnsCreated() throws Exception {
    mockMvc.perform(post("/api/admin/tags")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Java"
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.name").value("Java"));
  }

  @Test
  void createTagValidatesRequiredFields() throws Exception {
    mockMvc.perform(post("/api/admin/tags")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createTagRejectsConflictingName() throws Exception {
    adminTagRepository.save(sampleTag("Java"));

    mockMvc.perform(post("/api/admin/tags")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Java"
                }
                """))
        .andExpect(status().isConflict());
  }

  @Test
  void bulkCreateTagsCreatesNewTags() throws Exception {
    mockMvc.perform(post("/api/admin/tags/bulk")
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "names": ["Java", "Python"]
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("Java"))
        .andExpect(jsonPath("$[1].name").value("Python"));
  }

  @Test
  void getTagByIdReturnsTag() throws Exception {
    final Tag saved = adminTagRepository.save(sampleTag("Java"));

    mockMvc.perform(get("/api/admin/tags/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.name").value("Java"));
  }

  @Test
  void updateTagReturnsUpdated() throws Exception {
    final Tag saved = adminTagRepository.save(sampleTag("Java"));

    mockMvc.perform(put("/api/admin/tags/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Java Updated"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.id()))
        .andExpect(jsonPath("$.name").value("Java Updated"));
  }

  @Test
  void deleteTagReturnsNoContent() throws Exception {
    final Tag saved = adminTagRepository.save(sampleTag("Java"));

    mockMvc.perform(delete("/api/admin/tags/" + saved.id())
            .with(jwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  private static Tag sampleTag(final String name) {
    final Instant now = Instant.parse("2026-02-21T10:00:00Z");
    return new Tag(null, name, now, now, null);
  }
}
