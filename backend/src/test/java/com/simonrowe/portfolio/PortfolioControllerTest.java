package com.simonrowe.portfolio;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V048CreatePortfolioProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;

class PortfolioControllerTest extends AbstractIntegrationTest {

  @Autowired
  private PortfolioProjectRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void freshCollectionWithItsIndexes() {
    mongoTemplate.dropCollection(PortfolioProject.COLLECTION);
    V048CreatePortfolioProjects.createIndexes(mongoTemplate);
  }

  private static String project(final String slug, final String status, final boolean published) {
    return """
        {
          "slug": "%s",
          "name": "Name %s",
          "tagline": "Tagline %s",
          "description": "Secret description %s",
          "status": "%s",
          "published": %s,
          "image": { "url": "/uploads/%s.png" },
          "liveUrl": "https://%s.example",
          "accentHue": 120
        }
        """.formatted(slug, slug, slug, slug, status, published, slug, slug);
  }

  private String create(final String body) throws Exception {
    String response = mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  @Test
  void publicListShowsOnlyPublishedProjectsInOrderAndHidesComingSoonDetails() throws Exception {
    create(project("live-one", "LIVE", true));
    create(project("soon-one", "COMING_SOON", true));
    create(project("draft-one", "LIVE", false));

    mockMvc.perform(get("/api/portfolio"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].slug").value("live-one"))
        .andExpect(jsonPath("$[0].description").value("Secret description live-one"))
        .andExpect(jsonPath("$[0].liveUrl").value("https://live-one.example"))
        .andExpect(jsonPath("$[0].image.url").value("/uploads/live-one.png"))
        .andExpect(jsonPath("$[1].slug").value("soon-one"))
        .andExpect(jsonPath("$[1].status").value("COMING_SOON"))
        .andExpect(jsonPath("$[1].accentHue").value(120))
        .andExpect(jsonPath("$[1].description").doesNotExist())
        .andExpect(jsonPath("$[1].image").doesNotExist())
        .andExpect(jsonPath("$[1].liveUrl").doesNotExist());
  }

  @Test
  void detailIsNotFoundForComingSoonUnpublishedAndUnknownSlugs() throws Exception {
    create(project("live-one", "BETA", true));
    create(project("soon-one", "COMING_SOON", true));
    create(project("draft-one", "LIVE", false));

    mockMvc.perform(get("/api/portfolio/live-one"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("BETA"));
    for (String slug : new String[] {"soon-one", "draft-one", "nope"}) {
      mockMvc.perform(get("/api/portfolio/" + slug)).andExpect(status().isNotFound());
    }
  }

  @Test
  void everyFieldRoundTripsThroughTheEditor() throws Exception {
    String id = create(project("round-trip", "IN_DEVELOPMENT", true));

    mockMvc.perform(get("/api/admin/portfolio/" + id).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("round-trip"))
        .andExpect(jsonPath("$.name").value("Name round-trip"))
        .andExpect(jsonPath("$.tagline").value("Tagline round-trip"))
        .andExpect(jsonPath("$.description").value("Secret description round-trip"))
        .andExpect(jsonPath("$.status").value("IN_DEVELOPMENT"))
        .andExpect(jsonPath("$.published").value(true))
        .andExpect(jsonPath("$.image.url").value("/uploads/round-trip.png"))
        .andExpect(jsonPath("$.liveUrl").value("https://round-trip.example"))
        .andExpect(jsonPath("$.accentHue").value(120))
        .andExpect(jsonPath("$.displayOrder").value(0))
        .andExpect(jsonPath("$.createdAt").isNotEmpty());
  }

  @Test
  void updateReplacesFieldsButKeepsOrderAndCreatedAt() throws Exception {
    create(project("first", "LIVE", true));
    String id = create(project("second", "LIVE", true));
    var before = repository.findById(id).orElseThrow();

    mockMvc.perform(put("/api/admin/portfolio/" + id).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(project("second-renamed", "COMING_SOON", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("second-renamed"))
        .andExpect(jsonPath("$.displayOrder").value(1));
    var after = repository.findById(id).orElseThrow();
    assertThat(after.createdAt()).isEqualTo(before.createdAt());
    assertThat(after.published()).isFalse();
  }

  @Test
  void duplicateSlugIsRefusedByTheIndexWithConflict() throws Exception {
    create(project("taken", "LIVE", true));
    mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(project("taken", "BETA", true)))
        .andExpect(status().isConflict());
    String other = create(project("other", "LIVE", true));
    mockMvc.perform(put("/api/admin/portfolio/" + other).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(project("taken", "LIVE", true)))
        .andExpect(status().isConflict());
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void refusesAnInvalidSlugAndAnOffSiteNonHttpsLinkNamingTheFields() throws Exception {
    mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(project("Bad Slug", "LIVE", true)
                .replace("https://Bad Slug.example", "http://insecure.example")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("liveUrl"));
  }

  @Test
  void rejectsSlugsThatWouldBacktrackWithoutHanging() throws Exception {
    String hostile = "a-".repeat(29) + "!";
    mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(project(hostile, "LIVE", true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"));
  }

  @Test
  void reorderRewritesDisplayOrderAndRequiresEveryProjectOnce() throws Exception {
    String a = create(project("a", "LIVE", true));
    String b = create(project("b", "LIVE", true));
    String c = create(project("c", "LIVE", true));

    mockMvc.perform(patch("/api/admin/portfolio/reorder").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\": [\"%s\", \"%s\", \"%s\"]}".formatted(c, a, b)))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/portfolio"))
        .andExpect(jsonPath("$[0].slug").value("c"))
        .andExpect(jsonPath("$[1].slug").value("a"))
        .andExpect(jsonPath("$[2].slug").value("b"));

    mockMvc.perform(patch("/api/admin/portfolio/reorder").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\": [\"%s\", \"%s\"]}".formatted(a, a)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteRemovesTheProject() throws Exception {
    String id = create(project("gone", "LIVE", true));
    mockMvc.perform(delete("/api/admin/portfolio/" + id).with(adminJwt()))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/admin/portfolio/" + id).with(adminJwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void managingRequiresTheAdminRoleWhileReadingDoesNot() throws Exception {
    mockMvc.perform(get("/api/portfolio")).andExpect(status().isOk());
    mockMvc.perform(get("/api/admin/portfolio")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/admin/portfolio").with(jwt())
            .contentType(MediaType.APPLICATION_JSON).content(project("x", "LIVE", true)))
        .andExpect(status().isForbidden());
    assertThat(repository.count()).isZero();
  }
}
