package com.simonrowe.books;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.verify;
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
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.migration.changeunits.V062CreateBooks;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;

class BookControllerTest extends AbstractIntegrationTest {

  @Autowired
  private BookRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void freshCollectionWithItsIndexes() {
    mongoTemplate.dropCollection(Book.COLLECTION);
    V062CreateBooks.createIndexes(mongoTemplate);
  }

  /** A book with every field set, so a field the editor drops shows up as a failure. */
  private static String book(final String slug, final boolean published) {
    return """
        {
          "slug": "%s",
          "title": "Title %s",
          "subtitle": "Subtitle %s",
          "authors": [ "First Author", "  Second Author  " ],
          "publishedYear": 2004,
          "category": "Testing and design",
          "summary": "Summary %s.",
          "description": "## Heading\\n\\n    indented code\\n",
          "note": "Why %s is on the list.",
          "coverImage": { "url": "/uploads/%s.jpg" },
          "purchaseUrl": "https://www.amazon.co.uk/dp/%s",
          "sourceLabel": "A talk",
          "sourceUrl": "https://talk.example/%s",
          "published": %s
        }
        """.formatted(slug, slug, slug, slug, slug, slug, slug, slug, published);
  }

  private String create(final String body) throws Exception {
    String response = mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  @Test
  void publicListShowsOnlyPublishedBooksInOrderWithEverythingTheDrawerNeeds() throws Exception {
    create(book("first", true));
    create(book("draft", false));
    create(book("second", true));

    mockMvc.perform(get("/api/books"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].slug").value("first"))
        .andExpect(jsonPath("$[0].authors[1]").value("Second Author"))
        .andExpect(jsonPath("$[0].description").value("## Heading\n\n    indented code\n"))
        .andExpect(jsonPath("$[0].note").value("Why first is on the list."))
        .andExpect(jsonPath("$[0].coverImage.url").value("/uploads/first.jpg"))
        .andExpect(jsonPath("$[0].purchaseUrl").value("https://www.amazon.co.uk/dp/first"))
        .andExpect(jsonPath("$[0].id").doesNotExist())
        .andExpect(jsonPath("$[0].published").doesNotExist())
        .andExpect(jsonPath("$[1].slug").value("second"));
  }

  @Test
  void absentOptionalFieldsAreOmittedFromThePublicList() throws Exception {
    create("""
        { "slug": "bare", "title": "Bare", "authors": ["A"], "category": "C",
          "summary": "S.", "published": true }
        """);

    mockMvc.perform(get("/api/books"))
        .andExpect(jsonPath("$[0].slug").value("bare"))
        .andExpect(jsonPath("$[0].subtitle").doesNotExist())
        .andExpect(jsonPath("$[0].note").doesNotExist())
        .andExpect(jsonPath("$[0].coverImage").doesNotExist())
        .andExpect(jsonPath("$[0].purchaseUrl").doesNotExist());
  }

  @Test
  void everyFieldRoundTripsAndSurvivesAnUnchangedSave() throws Exception {
    String id = create(book("round-trip", true));
    String saved = mockMvc.perform(get("/api/admin/books/" + id).with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("round-trip"))
        .andExpect(jsonPath("$.title").value("Title round-trip"))
        .andExpect(jsonPath("$.subtitle").value("Subtitle round-trip"))
        .andExpect(jsonPath("$.authors[0]").value("First Author"))
        .andExpect(jsonPath("$.authors[1]").value("Second Author"))
        .andExpect(jsonPath("$.publishedYear").value(2004))
        .andExpect(jsonPath("$.category").value("Testing and design"))
        .andExpect(jsonPath("$.summary").value("Summary round-trip."))
        .andExpect(jsonPath("$.description").value("## Heading\n\n    indented code\n"))
        .andExpect(jsonPath("$.note").value("Why round-trip is on the list."))
        .andExpect(jsonPath("$.coverImage.url").value("/uploads/round-trip.jpg"))
        .andExpect(jsonPath("$.purchaseUrl").value("https://www.amazon.co.uk/dp/round-trip"))
        .andExpect(jsonPath("$.sourceLabel").value("A talk"))
        .andExpect(jsonPath("$.sourceUrl").value("https://talk.example/round-trip"))
        .andExpect(jsonPath("$.published").value(true))
        .andExpect(jsonPath("$.displayOrder").value(0))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andReturn().getResponse().getContentAsString();

    // Saving back exactly what the editor loaded must change nothing.
    mockMvc.perform(put("/api/admin/books/" + id).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(saved))
        .andExpect(status().isOk());
    String resaved = mockMvc.perform(get("/api/admin/books/" + id).with(adminJwt()))
        .andReturn().getResponse().getContentAsString();
    assertThat(withoutUpdatedAt(resaved)).isEqualTo(withoutUpdatedAt(saved));
  }

  private static Object withoutUpdatedAt(final String json) {
    Map<String, Object> map = JsonPath.read(json, "$");
    map.remove("updatedAt");
    return map;
  }

  @Test
  void updateReplacesFieldsButKeepsOrderAndCreatedAt() throws Exception {
    create(book("first", true));
    String id = create(book("second", true));
    var before = repository.findById(id).orElseThrow();

    mockMvc.perform(put("/api/admin/books/" + id).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(book("second-renamed", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("second-renamed"))
        .andExpect(jsonPath("$.displayOrder").value(1));
    var after = repository.findById(id).orElseThrow();
    assertThat(after.createdAt()).isEqualTo(before.createdAt());
    assertThat(after.published()).isFalse();
  }

  @Test
  void refusesBadFieldsNamingEachOne() throws Exception {
    String body = book("Bad Slug", true)
        .replace("\"First Author\", \"  Second Author  \"", "\"First Author\", \" \"")
        .replace("\"publishedYear\": 2004", "\"publishedYear\": 1200")
        .replace("\"category\": \"Testing and design\"", "\"category\": \"\"")
        .replace("https://www.amazon.co.uk/dp/Bad Slug", "http://amazon.example")
        .replace("/uploads/Bad Slug.jpg", "//evil.example/cover.jpg")
        .replace("https://talk.example/Bad Slug", "javascript:alert(1)");
    mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder(
            "slug", "authors[1]", "publishedYear", "category", "purchaseUrl", "coverImage",
            "sourceUrl")));
  }

  @Test
  void requiresAnAuthorTitleAndSummary() throws Exception {
    mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"slug\": \"empty\", \"category\": \"C\", \"authors\": [] }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder(
            "title", "authors", "summary")));
  }

  @Test
  void sourceLinkNeedsLabel() throws Exception {
    mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(book("unlabelled", true).replace("\"sourceLabel\": \"A talk\"",
                "\"sourceLabel\": \"\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("sourceLabel"));
  }

  @Test
  void rejectsSlugsThatWouldBacktrackWithoutHanging() throws Exception {
    String hostile = "a-".repeat(39) + "!";
    mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(book(hostile, true)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"));
  }

  @Test
  void duplicateSlugIsRefusedByTheIndexWithConflict() throws Exception {
    create(book("taken", true));
    mockMvc.perform(post("/api/admin/books").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(book("taken", false)))
        .andExpect(status().isConflict());
    String other = create(book("other", true));
    mockMvc.perform(put("/api/admin/books/" + other).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(book("taken", true)))
        .andExpect(status().isConflict());
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void reorderRewritesDisplayOrderKeepsEveryFieldAndRequiresEveryBookOnce() throws Exception {
    String a = create(book("a", true));
    String b = create(book("b", true));
    String c = create(book("c", true));

    mockMvc.perform(patch("/api/admin/books/reorder").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\": [\"%s\", \"%s\", \"%s\"]}".formatted(c, a, b)))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/books"))
        .andExpect(jsonPath("$[0].slug").value("c"))
        .andExpect(jsonPath("$[1].slug").value("a"))
        .andExpect(jsonPath("$[2].slug").value("b"));
    Book moved = repository.findById(a).orElseThrow();
    assertThat(moved.displayOrder()).isEqualTo(1);
    assertThat(moved.note()).isEqualTo("Why a is on the list.");
    assertThat(moved.authors()).hasSize(2);

    mockMvc.perform(patch("/api/admin/books/reorder").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderedIds\": [\"%s\", \"%s\"]}".formatted(a, a)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void everySaveAndDeleteIsAnnouncedSoSearchAndTheChatFollow() throws Exception {
    String id = create(book("announced", true));
    verify(contentChangePublisher).publishCreated(ContentType.BOOK, id);

    mockMvc.perform(put("/api/admin/books/" + id).with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(book("announced", false)))
        .andExpect(status().isOk());
    verify(contentChangePublisher).publishUpdated(ContentType.BOOK, id);

    mockMvc.perform(delete("/api/admin/books/" + id).with(adminJwt()))
        .andExpect(status().isNoContent());
    verify(contentChangePublisher).publishDeleted(ContentType.BOOK, id);
  }

  @Test
  void deleteRemovesTheBook() throws Exception {
    String id = create(book("gone", true));
    mockMvc.perform(delete("/api/admin/books/" + id).with(adminJwt()))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/admin/books/" + id).with(adminJwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void managingRequiresTheAdminRoleWhileReadingDoesNot() throws Exception {
    mockMvc.perform(get("/api/books")).andExpect(status().isOk());
    mockMvc.perform(get("/api/admin/books")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/admin/books").with(jwt())
            .contentType(MediaType.APPLICATION_JSON).content(book("x", true)))
        .andExpect(status().isForbidden());
    assertThat(repository.count()).isZero();
  }
}
