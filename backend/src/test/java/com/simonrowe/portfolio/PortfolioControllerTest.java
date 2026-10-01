package com.simonrowe.portfolio;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
import java.util.Map;
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

  /** A project with every page field set, so a field the editor drops shows up as a failure. */
  private static String fullProject(final String slug, final String status) {
    return """
        {
          "slug": "%s",
          "name": "Full %s",
          "tagline": "Tagline",
          "status": "%s",
          "published": true,
          "image": { "url": "/media/full/hero.webp" },
          "liveUrl": "https://full.example",
          "accentHue": 152,
          "headline": "Line one,\\nline two.",
          "summary": "A summary.",
          "statement": {
            "label": "Why",
            "text": "Because.",
            "points": [ { "title": "Point", "text": "Point text." } ]
          },
          "exampleQuestions": [ "When is half term?", "  What's on?  " ],
          "highlights": [ {
            "title": "Highlight", "text": "Highlight text.",
            "imageUrl": "/media/full/one.webp", "imageAlt": "Alt text"
          } ],
          "demo": {
            "title": "Demo", "summary": "Demo summary.",
            "videoUrl": "/media/full/demo.mp4", "captionsUrl": "/media/full/demo.vtt",
            "posterUrl": "https://cdn.example/poster.webp",
            "chapters": [ { "startSeconds": 0, "label": "Start" },
                          { "startSeconds": 42, "label": "Later" } ]
          },
          "pages": [ {
            "slug": "how-it-works", "title": "How it works", "navHint": "sources",
            "summary": "Page summary.", "body": "## Heading\\n\\n    indented code\\n"
          } ]
        }
        """.formatted(slug, slug, status);
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
  void everyPageFieldRoundTripsAndSurvivesAnUnchangedSave() throws Exception {
    String id = create(fullProject("full", "BETA"));
    String saved = mockMvc.perform(get("/api/admin/portfolio/" + id).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headline").value("Line one,\nline two."))
        .andExpect(jsonPath("$.summary").value("A summary."))
        .andExpect(jsonPath("$.statement.label").value("Why"))
        .andExpect(jsonPath("$.statement.text").value("Because."))
        .andExpect(jsonPath("$.statement.points[0].title").value("Point"))
        .andExpect(jsonPath("$.statement.points[0].text").value("Point text."))
        .andExpect(jsonPath("$.exampleQuestions[0]").value("When is half term?"))
        .andExpect(jsonPath("$.exampleQuestions[1]").value("What's on?"))
        .andExpect(jsonPath("$.highlights[0].title").value("Highlight"))
        .andExpect(jsonPath("$.highlights[0].text").value("Highlight text."))
        .andExpect(jsonPath("$.highlights[0].imageUrl").value("/media/full/one.webp"))
        .andExpect(jsonPath("$.highlights[0].imageAlt").value("Alt text"))
        .andExpect(jsonPath("$.demo.title").value("Demo"))
        .andExpect(jsonPath("$.demo.summary").value("Demo summary."))
        .andExpect(jsonPath("$.demo.videoUrl").value("/media/full/demo.mp4"))
        .andExpect(jsonPath("$.demo.captionsUrl").value("/media/full/demo.vtt"))
        .andExpect(jsonPath("$.demo.posterUrl").value("https://cdn.example/poster.webp"))
        .andExpect(jsonPath("$.demo.chapters[1].startSeconds").value(42))
        .andExpect(jsonPath("$.demo.chapters[1].label").value("Later"))
        .andExpect(jsonPath("$.pages[0].slug").value("how-it-works"))
        .andExpect(jsonPath("$.pages[0].title").value("How it works"))
        .andExpect(jsonPath("$.pages[0].navHint").value("sources"))
        .andExpect(jsonPath("$.pages[0].summary").value("Page summary."))
        .andExpect(jsonPath("$.pages[0].body").value("## Heading\n\n    indented code\n"))
        .andReturn().getResponse().getContentAsString();

    // Saving back exactly what the editor loaded must change nothing.
    mockMvc.perform(put("/api/admin/portfolio/" + id).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(saved))
        .andExpect(status().isOk());
    String resaved = mockMvc.perform(get("/api/admin/portfolio/" + id).with(adminJwt()))
        .andReturn().getResponse().getContentAsString();
    assertThat(withoutUpdatedAt(resaved)).isEqualTo(withoutUpdatedAt(saved));

    mockMvc.perform(get("/api/portfolio/full"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pages[0].body").value("## Heading\n\n    indented code\n"))
        .andExpect(jsonPath("$.demo.chapters.length()").value(2))
        .andExpect(jsonPath("$.exampleQuestions.length()").value(2));
  }

  private static Object withoutUpdatedAt(final String json) {
    Map<String, Object> map = JsonPath.read(json, "$");
    map.remove("updatedAt");
    return map;
  }

  @Test
  void comingSoonWithholdsEveryPageFieldAndEmptyListsAreOmitted() throws Exception {
    create(fullProject("hidden", "COMING_SOON"));
    create(project("plain", "LIVE", true));

    mockMvc.perform(get("/api/portfolio"))
        .andExpect(jsonPath("$[0].slug").value("hidden"))
        .andExpect(jsonPath("$[0].headline").doesNotExist())
        .andExpect(jsonPath("$[0].summary").doesNotExist())
        .andExpect(jsonPath("$[0].statement").doesNotExist())
        .andExpect(jsonPath("$[0].exampleQuestions").doesNotExist())
        .andExpect(jsonPath("$[0].highlights").doesNotExist())
        .andExpect(jsonPath("$[0].demo").doesNotExist())
        .andExpect(jsonPath("$[0].pages").doesNotExist())
        .andExpect(jsonPath("$[1].slug").value("plain"))
        .andExpect(jsonPath("$[1].pages").doesNotExist())
        .andExpect(jsonPath("$[1].exampleQuestions").doesNotExist());
  }

  @Test
  void refusesBadPageFieldsNamingEachOne() throws Exception {
    String body = fullProject("bad", "BETA")
        .replace("\"slug\": \"how-it-works\"", "\"slug\": \"How It Works\"")
        .replace("/media/full/one.webp", "//evil.example/x.webp")
        .replace("\"videoUrl\": \"/media/full/demo.mp4\"", "\"videoUrl\": \"\"")
        .replace("\"startSeconds\": 42", "\"startSeconds\": -1");
    mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder(
            "highlights[0].imageUrl", "demo.videoUrl", "demo.chapters[1].startSeconds",
            "pages[0].slug")));
  }

  @Test
  void refusesTwoPagesWithTheSameSlug() throws Exception {
    String page = "{ \"slug\": \"same\", \"title\": \"Same\" }";
    String body = project("dupe-pages", "LIVE", true)
        .replace("\"accentHue\": 120",
            "\"accentHue\": 120, \"pages\": [" + page + ", " + page + "]");
    mockMvc.perform(post("/api/admin/portfolio").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("pages[1].slug"));
  }

  @Test
  void reorderKeepsEveryPageField() throws Exception {
    String a = create(fullProject("full-a", "LIVE"));
    String b = create(project("plain-b", "LIVE", true));
    mockMvc.perform(patch("/api/admin/portfolio/reorder").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\": [\"%s\", \"%s\"]}".formatted(b, a)))
        .andExpect(status().isNoContent());
    var moved = repository.findById(a).orElseThrow();
    assertThat(moved.displayOrder()).isEqualTo(1);
    assertThat(moved.pages()).hasSize(1);
    assertThat(moved.demo().chapters()).hasSize(2);
    assertThat(moved.statement().points()).hasSize(1);
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
