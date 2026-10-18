package com.simonrowe.snippet;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.migration.changeunits.V061CreateSnippets;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** Snippets end to end, against a real Mongo: keeping, editing, finding and who may. */
class SnippetApiTest extends AbstractIntegrationTest {

  private static final String URL = "/api/admin/snippets";
  private static final String BODY = """
      Martin says engineers should **review what it is required to do**.

      Requirements and UAT remain human responsibilities; guardrails are written before \
      the code exists.
      """;
  private static final Instant PUBLISHED = Instant.parse("2026-10-12T20:37:19Z");

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private AggregatedArticleRepository articleRepository;
  @Autowired private ArticleSummaryRepository summaryRepository;
  @Autowired private SnippetRepository snippetRepository;
  @Autowired private ObjectMapper objectMapper;

  /**
   * Leaves nothing behind for the next class on the shared Mongo: other tests count the
   * articles and READY summaries they see (`/api/news/summaries/ids` must start empty).
   */
  @AfterEach
  void removeWhatThisClassWrote() {
    mongoTemplate.dropCollection(Snippet.COLLECTION);
    articleRepository.deleteAll();
    summaryRepository.deleteAll();
  }

  @BeforeEach
  void freshCollections() {
    mongoTemplate.dropCollection(Snippet.COLLECTION);
    V061CreateSnippets.createIndexes(mongoTemplate);
    articleRepository.deleteAll();
    summaryRepository.deleteAll();
    articleRepository.save(article("pr", "Human PR Review Is Next", "TLDR Dev"));
    summaryRepository.save(readySummary("pr", BODY));
  }

  @Test
  void keepsPassageWithArticleDetailsTheServerLooksUp() throws Exception {
    mockMvc.perform(json(post(URL), new CreateSnippetRequest("pr",
            "engineers should review what it is required to do", "Martin says ", ".\n\nReq",
            " For the review slide ", List.of("Platform Eng Talk", "#platform-eng-talk"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.contentId").value("pr"))
        .andExpect(jsonPath("$.exact").value("engineers should review what it is required to do"))
        .andExpect(jsonPath("$.prefix").value("Martin says "))
        .andExpect(jsonPath("$.note").value("For the review slide"))
        .andExpect(jsonPath("$.tags", contains("platform-eng-talk")))
        .andExpect(jsonPath("$.articleTitle").value("Human PR Review Is Next"))
        .andExpect(jsonPath("$.sourceName").value("TLDR Dev"))
        .andExpect(jsonPath("$.author").value("William Martin"))
        .andExpect(jsonPath("$.originalUrl").value("https://example.com/pr"))
        .andExpect(jsonPath("$.articlePublishedAt").value(PUBLISHED.toString()))
        .andExpect(jsonPath("$.inCurrentSummary").value(true))
        .andExpect(jsonPath("$.articleAvailable").value(true))
        .andExpect(jsonPath("$.createdBy").doesNotExist());

    Snippet stored = snippetRepository.findAll().getFirst();
    assertThat(stored.createdBy()).isEqualTo("owner");
    assertThat(stored.summaryId()).isEqualTo(summaryRepository.findAll().getFirst().id());
    assertThat(stored.sourceType()).isEqualTo(SnippetSourceType.ARTICLE_SUMMARY);
  }

  @Test
  void acceptsQuoteAcrossParagraphBreak() throws Exception {
    mockMvc.perform(json(post(URL), request("pr",
            "required to do.\n\nRequirements and UAT")))
        .andExpect(status().isCreated());
  }

  @Test
  void refusesPassageTheSummaryNeverSaid() throws Exception {
    mockMvc.perform(json(post(URL), request("pr", "review models should differ")))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.message").value(
            "That passage is not in this article's summary. Reopen the summary and try again."));
    assertThat(snippetRepository.count()).isZero();
  }

  @Test
  void refusesAnArticleWithNoFinishedSummaryOrNoArticleAtAll() throws Exception {
    articleRepository.save(article("cold", "Not yet summarised", "TLDR AI"));

    mockMvc.perform(json(post(URL), request("cold", "anything")))
        .andExpect(status().isConflict());
    mockMvc.perform(json(post(URL), request("missing", "anything")))
        .andExpect(status().isNotFound());
    assertThat(snippetRepository.count()).isZero();
  }

  @Test
  void refusesBadInputWithReasonRatherThanCuttingItShort() throws Exception {
    mockMvc.perform(json(post(URL), request("pr", "  ")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(json(post(URL), request("pr", "x".repeat(2_001))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            "A snippet can be at most 2,000 characters. Select a shorter passage."));
    mockMvc.perform(json(post(URL), new CreateSnippetRequest("pr",
            "engineers should", "p".repeat(65), "", null, null)))
        .andExpect(status().isBadRequest());
    mockMvc.perform(json(post(URL), new CreateSnippetRequest("pr",
            "engineers should", "", "", "n".repeat(2_001), null)))
        .andExpect(status().isBadRequest());
    assertThat(snippetRepository.count()).isZero();
  }

  /** The update rebuilds the whole record, so every other field must come through intact. */
  @Test
  void editingTheNoteAndTagsKeepsEverythingElse() throws Exception {
    Snippet before = keep("engineers should review what it is required to do", "first",
        List.of("a"));

    mockMvc.perform(json(put(URL + "/" + before.id()),
            new UpdateSnippetRequest("  second  ", List.of("B", "c"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").value("second"))
        .andExpect(jsonPath("$.tags", contains("b", "c")));

    Snippet after = snippetRepository.findById(before.id()).orElseThrow();
    assertThat(after)
        .usingRecursiveComparison()
        .ignoringFields("note", "tags", "updatedAt")
        .isEqualTo(before);
    assertThat(after.updatedAt()).isAfterOrEqualTo(before.updatedAt());
  }

  @Test
  void editingOrDeletingSomethingThatIsNotThere() throws Exception {
    mockMvc.perform(json(put(URL + "/nope"), new UpdateSnippetRequest("x", List.of())))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete(URL + "/nope").with(owner()))
        .andExpect(status().isNoContent());
  }

  @Test
  void deletesSnippet() throws Exception {
    Snippet kept = keep("engineers should", "", List.of());

    mockMvc.perform(delete(URL + "/" + kept.id()).with(owner()))
        .andExpect(status().isNoContent());

    assertThat(snippetRepository.count()).isZero();
  }

  @Test
  void listsOneArticlesSnippetsAndSaysWhichAreStillInTheSummary() throws Exception {
    store("pr", "engineers should review", Instant.parse("2026-10-17T10:00:00Z"));
    store("pr", "guardrails are written before the code exists",
        Instant.parse("2026-10-17T10:05:00Z"));
    // The summary is regenerated and one of the passages is no longer in it.
    summaryRepository.save(readySummary("pr", "Martin says engineers should review it all."));

    mockMvc.perform(get(URL).param("contentId", "pr").with(owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].exact").value("engineers should review"))
        .andExpect(jsonPath("$[0].inCurrentSummary").value(true))
        .andExpect(jsonPath("$[1].inCurrentSummary").value(false));
  }

  @Test
  void snippetOutlivesItsArticle() throws Exception {
    keep("engineers should review", "", List.of());
    articleRepository.deleteById("pr");
    summaryRepository.deleteAll();

    mockMvc.perform(get(URL).with(owner()))
        .andExpect(jsonPath("$.content[0].articleTitle").value("Human PR Review Is Next"))
        .andExpect(jsonPath("$.content[0].articleAvailable").value(false))
        .andExpect(jsonPath("$.content[0].inCurrentSummary").value(false));
  }

  @Test
  void searchesEveryWordAcrossQuoteNoteTagsTitleAndSource() throws Exception {
    articleRepository.save(article("meta", "How Meta Built Forum", "Meta Engineering"));
    summaryRepository.save(readySummary("meta",
        "Success was measured by verified outcomes, not the quantity of code (C++ included)."));
    keep("engineers should review", "opening slide", List.of("talk"));
    keepFor("meta", "measured by verified outcomes", "counter to LOC metrics", List.of("talk",
        "metrics"));
    keepFor("meta", "quantity of code (C++ included)", "", List.of());

    assertSearch(get(URL).param("q", "OPENING review"), "engineers should review");
    assertSearch(get(URL).param("q", "metrics forum"), "measured by verified outcomes");
    // Quoted literally: no regex quantifier reaches the engine.
    assertSearch(get(URL).param("q", "c++"), "quantity of code (C++ included)");
    assertSearch(get(URL).param("tag", "talk").param("tag", "Metrics"),
        "measured by verified outcomes");
    assertSearch(get(URL).param("source", "Meta Engineering").param("q", "verified"),
        "measured by verified outcomes");
  }

  @Test
  void sortsByNewestSnippetOrNewestArticleAndFiltersByTheDayItWasKept() throws Exception {
    articleRepository.save(article("newer", "A newer article", "TLDR AI",
        Instant.parse("2026-10-16T00:00:00Z")));
    summaryRepository.save(readySummary("newer", "Fresh words."));
    store("pr", "engineers should", Instant.parse("2026-10-17T10:00:00Z"));
    store("newer", "Fresh words.", Instant.parse("2026-10-15T10:00:00Z"));

    mockMvc.perform(get(URL).with(owner()))
        .andExpect(jsonPath("$.content[*].contentId", contains("pr", "newer")));
    mockMvc.perform(get(URL).param("sort", "article").with(owner()))
        .andExpect(jsonPath("$.content[*].contentId", contains("newer", "pr")));
    mockMvc.perform(get(URL).param("from", "2026-10-16").with(owner()))
        .andExpect(jsonPath("$.content[*].contentId", contains("pr")));
    mockMvc.perform(get(URL).param("to", "2026-10-15").with(owner()))
        .andExpect(jsonPath("$.content[*].contentId", contains("newer")));
  }

  @Test
  void countsSnippetsPerArticleAndPerTagAndListsTheirSources() throws Exception {
    articleRepository.save(article("meta", "How Meta Built Forum", "Meta Engineering"));
    summaryRepository.save(readySummary("meta", "Verified outcomes."));
    keep("engineers should", "", List.of("talk", "review"));
    keep("guardrails are written", "", List.of("talk"));
    keepFor("meta", "Verified outcomes.", "", List.of("metrics"));

    mockMvc.perform(get(URL + "/counts").with(owner()))
        .andExpect(jsonPath("$.pr").value(2))
        .andExpect(jsonPath("$.meta").value(1));
    mockMvc.perform(get(URL + "/tags").with(owner()))
        .andExpect(jsonPath("$[*].tag", contains("talk", "metrics", "review")))
        .andExpect(jsonPath("$[0].count").value(2));
    mockMvc.perform(get(URL + "/sources").with(owner()))
        .andExpect(jsonPath("$", contains("Meta Engineering", "TLDR Dev")));
  }

  /** Unlike favourites, nothing here is public: every route needs the admin role. */
  @Test
  void onlyTheAdminRoleCanReadOrWrite() throws Exception {
    keep("engineers should", "", List.of());

    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mockMvc.perform(get(URL).param("contentId", "pr")).andExpect(status().isUnauthorized());
    mockMvc.perform(get(URL + "/counts").with(jwt())).andExpect(status().isForbidden());
    mockMvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request("pr", "engineers should"))))
        .andExpect(status().isForbidden());
    assertThat(snippetRepository.count()).isEqualTo(1);
  }

  private void assertSearch(final MockHttpServletRequestBuilder search, final String... exact)
      throws Exception {
    mockMvc.perform(search.with(owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].exact", contains(exact)));
  }

  private Snippet keep(final String exact, final String note, final List<String> tags)
      throws Exception {
    return keepFor("pr", exact, note, tags);
  }

  private Snippet keepFor(final String contentId, final String exact, final String note,
      final List<String> tags) throws Exception {
    String response = mockMvc.perform(json(post(URL),
            new CreateSnippetRequest(contentId, exact, "", "", note, tags)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String id = objectMapper.readTree(response).get("id").asString();
    return snippetRepository.findById(id).orElseThrow();
  }

  private void store(final String contentId, final String exact, final Instant createdAt) {
    AggregatedArticle article = articleRepository.findById(contentId).orElseThrow();
    snippetRepository.save(new Snippet(null, SnippetSourceType.ARTICLE_SUMMARY, contentId,
        "summary-" + contentId, exact, "", "", "", List.of(), article.title(),
        article.sourceName(), article.author(), article.originalUrl(), article.publishedDate(),
        "owner", createdAt, createdAt));
  }

  private MockHttpServletRequestBuilder json(
      final MockHttpServletRequestBuilder request, final Object body) throws Exception {
    return request.with(owner()).contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private static CreateSnippetRequest request(final String contentId, final String exact) {
    return new CreateSnippetRequest(contentId, exact, "", "", null, null);
  }

  private static JwtRequestPostProcessor owner() {
    return adminJwt().jwt(token -> token.subject("owner"));
  }

  private static AggregatedArticle article(
      final String id, final String title, final String source) {
    return article(id, title, source, PUBLISHED);
  }

  private static AggregatedArticle article(
      final String id, final String title, final String source, final Instant published) {
    return new AggregatedArticle(id, title, source, "https://example.com/feed",
        "https://example.com/" + id, "Card summary", "Full text", "William Martin", published,
        published, true, null);
  }

  private static ArticleSummary readySummary(final String articleId, final String body) {
    ArticleSummary summary = new ArticleSummary(
        ArticleSummary.idFor("article-summary-v1", articleId), articleId, Instant.now());
    summary.markReady(body, "test-model", 4_200, Instant.now());
    return summary;
  }
}
