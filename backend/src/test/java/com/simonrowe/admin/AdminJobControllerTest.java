package com.simonrowe.admin;

import static com.simonrowe.AdminTestAuth.adminJwt;
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

class AdminJobControllerTest extends AbstractIntegrationTest {

  @Autowired
  private AdminJobRepository adminJobRepository;

  @BeforeEach
  void setup() {
    adminJobRepository.deleteAll();
  }

  @Test
  void listJobsReturnsPaginatedResults() throws Exception {
    adminJobRepository.saveAll(List.of(
        sampleJob("j-1", "Software Engineer", "Acme Corp", false),
        sampleJob("j-2", "BSc Computer Science", "University of Leeds", true)
    ));

    mockMvc.perform(get("/api/admin/jobs")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void createJobReturnsCreated() throws Exception {
    String body = """
        {
            "title": "Backend Engineer",
            "company": "Acme Corp",
            "companyUrl": "https://acme.example.com",
            "startDate": "2022-01-01",
            "location": "London",
            "shortDescription": "Worked on backend systems",
            "education": false,
            "includeOnResume": true,
            "skills": []
        }
        """;

    mockMvc.perform(post("/api/admin/jobs")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("Backend Engineer"))
        .andExpect(jsonPath("$.company").value("Acme Corp"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void createJobValidatesRequiredFields() throws Exception {
    String body = """
        {
            "company": "Acme Corp",
            "startDate": "2022-01-01",
            "shortDescription": "Some description"
        }
        """;

    mockMvc.perform(post("/api/admin/jobs")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getJobByIdReturnsJob() throws Exception {
    adminJobRepository.save(sampleJob("j-1", "Lead Engineer", "Acme Corp", false));

    mockMvc.perform(get("/api/admin/jobs/j-1")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("j-1"))
        .andExpect(jsonPath("$.title").value("Lead Engineer"))
        .andExpect(jsonPath("$.company").value("Acme Corp"));
  }

  @Test
  void getJobByIdReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/admin/jobs/nonexistent")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateJobReturnsUpdated() throws Exception {
    adminJobRepository.save(sampleJob("j-1", "Original Title", "Old Company", false));

    String body = """
        {
            "title": "Updated Title",
            "company": "New Company",
            "startDate": "2023-01-01",
            "location": "Manchester",
            "shortDescription": "Updated short description",
            "education": false,
            "includeOnResume": true,
            "skills": []
        }
        """;

    mockMvc.perform(put("/api/admin/jobs/j-1")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("j-1"))
        .andExpect(jsonPath("$.title").value("Updated Title"))
        .andExpect(jsonPath("$.company").value("New Company"));
  }

  @Test
  void deleteJobReturnsNoContent() throws Exception {
    adminJobRepository.save(sampleJob("j-1", "Job To Delete", "Acme Corp", false));

    mockMvc.perform(delete("/api/admin/jobs/j-1")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void listJobsFiltersByEducation() throws Exception {
    adminJobRepository.saveAll(List.of(
        sampleJob("j-1", "Software Engineer", "Acme Corp", false),
        sampleJob("j-2", "BSc Computer Science", "University of Leeds", true),
        sampleJob("j-3", "MSc Data Science", "University of Manchester", true)
    ));

    mockMvc.perform(get("/api/admin/jobs?education=true")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  private static Job sampleJob(
      final String id,
      final String title,
      final String company,
      final boolean education
  ) {
    Instant now = Instant.parse("2024-06-01T10:00:00Z");
    return new Job(
        id,
        title,
        company,
        null,
        null,
        "2022-01-01",
        null,
        "London",
        "Short description of " + title,
        "Long description of " + title,
        education,
        true,
        List.of(),
        now,
        now,
        null
    );
  }
}
