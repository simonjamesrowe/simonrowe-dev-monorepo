package com.simonrowe.aggregation;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.WeeklyDigestAgent;
import com.simonrowe.embedding.EmbeddingService;
import com.simonrowe.search.IndexService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AdminAggregationControllerTest extends AbstractIntegrationTest {

  @MockitoBean
  private ContentAggregationAgent contentAggregationAgent;

  @MockitoBean
  private WeeklyDigestAgent weeklyDigestAgent;

  @MockitoBean
  private IndexService indexService;

  @MockitoBean
  private EmbeddingService embeddingService;

  @Autowired
  private AggregatedArticleRepository articleRepository;

  @Autowired
  private AggregatedEventRepository eventRepository;

  @Autowired
  private ContentSourceRepository sourceRepository;

  @AfterEach
  void tearDown() {
    articleRepository.deleteAll();
    eventRepository.deleteAll();
    sourceRepository.deleteAll();
  }

  // --- Content sources ---

  @Test
  void listSourcesReturnsAllSources() throws Exception {
    sourceRepository.saveAll(List.of(
        sampleSource("s-1", "Tech Blog", true),
        sampleSource("s-2", "Events Site", false)
    ));

    mockMvc.perform(get("/api/admin/content-sources")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void listSourcesRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/admin/content-sources"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listSourcesReturnsEmptyListWhenNoneExist() throws Exception {
    mockMvc.perform(get("/api/admin/content-sources")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void updateSourceChangesActiveFlag() throws Exception {
    sourceRepository.save(sampleSource("s-1", "Tech Blog", true));

    String body = """
        {"active": false}
        """;

    mockMvc.perform(put("/api/admin/content-sources/s-1")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("s-1"))
        .andExpect(jsonPath("$.name").value("Tech Blog"))
        .andExpect(jsonPath("$.active").value(false));
  }

  @Test
  void updateSourceChangesFeedUrl() throws Exception {
    sourceRepository.save(sampleSource("s-1", "Tech Blog", true));

    String body = """
        {"feedUrl": "https://new-feed.example.com/rss"}
        """;

    mockMvc.perform(put("/api/admin/content-sources/s-1")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feedUrl").value("https://new-feed.example.com/rss"));
  }

  @Test
  void updateSourcePreservesCategoryFilterWhenNotSupplied() throws Exception {
    sourceRepository.save(sampleSource("s-1", "OpenAI Engineering", true, "Engineering"));

    String body = """
        {"active": false}
        """;

    // A PUT that says nothing about the filter must not clear it. Dropping it here
    // would silently widen the source from one section to the publisher's whole feed.
    mockMvc.perform(put("/api/admin/content-sources/s-1")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.categoryFilter").value("Engineering"));
  }

  @Test
  void updateSourceChangesCategoryFilterWhenSupplied() throws Exception {
    sourceRepository.save(sampleSource("s-1", "OpenAI Engineering", true, "Engineering"));

    String body = """
        {"categoryFilter": "Research"}
        """;

    mockMvc.perform(put("/api/admin/content-sources/s-1")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryFilter").value("Research"));
  }

  @Test
  void updateSourceReturnsNotFoundForMissingId() throws Exception {
    String body = """
        {"active": false}
        """;

    mockMvc.perform(put("/api/admin/content-sources/nonexistent")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateSourceRequiresAuth() throws Exception {
    String body = """
        {"active": false}
        """;

    mockMvc.perform(put("/api/admin/content-sources/s-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
  }

  // --- Aggregation trigger ---

  @Test
  void triggerAggregationReturnsAccepted() throws Exception {
    mockMvc.perform(post("/api/admin/aggregation/trigger")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value("Content aggregation triggered"));
  }

  @Test
  void triggerAggregationRequiresAuth() throws Exception {
    mockMvc.perform(post("/api/admin/aggregation/trigger"))
        .andExpect(status().isUnauthorized());
  }

  // --- Digest trigger ---

  @Test
  void triggerDigestReturnsAccepted() throws Exception {
    mockMvc.perform(post("/api/admin/digest/trigger")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value("Weekly digest generation triggered"));
  }

  @Test
  void triggerDigestRequiresAuth() throws Exception {
    mockMvc.perform(post("/api/admin/digest/trigger"))
        .andExpect(status().isUnauthorized());
  }

  // --- Aggregated articles (news) ---

  @Test
  void listNewsReturnsAllArticles() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "First Article", true),
        sampleArticle("a-2", "Second Article", false)
    ));

    mockMvc.perform(get("/api/admin/news")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.number").value(0));
  }

  @Test
  void listNewsRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/admin/news"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listNewsReturnsEmptyListWhenNoneExist() throws Exception {
    mockMvc.perform(get("/api/admin/news")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @Test
  void listNewsIncludesBothVisibleAndHiddenArticles() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Visible Article", true));
    articleRepository.save(sampleArticle("a-2", "Hidden Article", false));

    mockMvc.perform(get("/api/admin/news")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void listNewsHonoursPageAndSizeParams() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "Oldest Article", true, Instant.parse("2026-01-01T10:00:00Z")),
        sampleArticle("a-2", "Middle Article", true, Instant.parse("2026-02-01T10:00:00Z")),
        sampleArticle("a-3", "Newest Article", true, Instant.parse("2026-03-01T10:00:00Z"))
    ));

    mockMvc.perform(get("/api/admin/news")
            .param("page", "0")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value("a-3"))
        .andExpect(jsonPath("$.content[1].id").value("a-2"))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.number").value(0));

    mockMvc.perform(get("/api/admin/news")
            .param("page", "1")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.number").value(1));
  }

  @Test
  void listNewsReturnsArticlesSortedByPublishedDateDescending() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "Middle Article", true, Instant.parse("2026-02-01T10:00:00Z")),
        sampleArticle("a-2", "Newest Article", true, Instant.parse("2026-03-01T10:00:00Z")),
        sampleArticle("a-3", "Oldest Article", true, Instant.parse("2026-01-01T10:00:00Z"))
    ));

    mockMvc.perform(get("/api/admin/news")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].title").value("Newest Article"))
        .andExpect(jsonPath("$.content[1].title").value("Middle Article"))
        .andExpect(jsonPath("$.content[2].title").value("Oldest Article"));
  }

  @Test
  void listNewsOutOfRangePageReturnsEmptyContent() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "First Article", true),
        sampleArticle("a-2", "Second Article", true)
    ));

    mockMvc.perform(get("/api/admin/news")
            .param("page", "5")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.number").value(5));
  }

  @Test
  void deleteArticleReturnsNoContent() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Article To Delete", true));

    mockMvc.perform(delete("/api/admin/news/a-1")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteArticleRequiresAuth() throws Exception {
    mockMvc.perform(delete("/api/admin/news/a-1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deleteNonexistentArticleReturnsNoContent() throws Exception {
    mockMvc.perform(delete("/api/admin/news/nonexistent")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateArticleVisibilityHidesArticle() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Visible Article", true));

    String body = """
        {"visible": false}
        """;

    mockMvc.perform(put("/api/admin/news/a-1/visibility")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("a-1"))
        .andExpect(jsonPath("$.visible").value(false));
  }

  @Test
  void updateArticleVisibilityReturnsNotFoundForMissingId() throws Exception {
    String body = """
        {"visible": false}
        """;

    mockMvc.perform(put("/api/admin/news/nonexistent/visibility")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isNotFound());
  }

  // --- Aggregated events ---

  @Test
  void listEventsReturnsAllEvents() throws Exception {
    eventRepository.saveAll(List.of(
        sampleEvent("e-1", "First Conference", true),
        sampleEvent("e-2", "Hidden Meetup", false)
    ));

    mockMvc.perform(get("/api/admin/events")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.number").value(0));
  }

  @Test
  void listEventsRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/admin/events"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listEventsReturnsEmptyListWhenNoneExist() throws Exception {
    mockMvc.perform(get("/api/admin/events")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }

  @Test
  void listEventsIncludesBothVisibleAndHiddenEvents() throws Exception {
    eventRepository.save(sampleEvent("e-1", "Visible Event", true));
    eventRepository.save(sampleEvent("e-2", "Hidden Event", false));

    mockMvc.perform(get("/api/admin/events")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void listEventsHonoursPageAndSizeParams() throws Exception {
    eventRepository.saveAll(List.of(
        sampleEvent("e-1", "Oldest Event", true, Instant.parse("2026-04-01T09:00:00Z")),
        sampleEvent("e-2", "Middle Event", true, Instant.parse("2026-05-01T09:00:00Z")),
        sampleEvent("e-3", "Newest Event", true, Instant.parse("2026-06-01T09:00:00Z"))
    ));

    mockMvc.perform(get("/api/admin/events")
            .param("page", "0")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value("e-3"))
        .andExpect(jsonPath("$.content[1].id").value("e-2"))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.number").value(0));

    mockMvc.perform(get("/api/admin/events")
            .param("page", "1")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.number").value(1));
  }

  @Test
  void listEventsReturnsEventsSortedByEventDateDescending() throws Exception {
    eventRepository.saveAll(List.of(
        sampleEvent("e-1", "Middle Event", true, Instant.parse("2026-05-01T09:00:00Z")),
        sampleEvent("e-2", "Newest Event", true, Instant.parse("2026-06-01T09:00:00Z")),
        sampleEvent("e-3", "Oldest Event", true, Instant.parse("2026-04-01T09:00:00Z"))
    ));

    mockMvc.perform(get("/api/admin/events")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].title").value("Newest Event"))
        .andExpect(jsonPath("$.content[1].title").value("Middle Event"))
        .andExpect(jsonPath("$.content[2].title").value("Oldest Event"));
  }

  @Test
  void listEventsOutOfRangePageReturnsEmptyContent() throws Exception {
    eventRepository.saveAll(List.of(
        sampleEvent("e-1", "First Conference", true),
        sampleEvent("e-2", "Second Conference", true)
    ));

    mockMvc.perform(get("/api/admin/events")
            .param("page", "5")
            .param("size", "2")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.number").value(5));
  }

  @Test
  void deleteEventReturnsNoContent() throws Exception {
    eventRepository.save(sampleEvent("e-1", "Event To Delete", true));

    mockMvc.perform(delete("/api/admin/events/e-1")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteEventRequiresAuth() throws Exception {
    mockMvc.perform(delete("/api/admin/events/e-1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deleteNonexistentEventReturnsNoContent() throws Exception {
    mockMvc.perform(delete("/api/admin/events/nonexistent")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateEventVisibilityHidesEvent() throws Exception {
    eventRepository.save(sampleEvent("e-1", "Visible Event", true));

    String body = """
        {"visible": false}
        """;

    mockMvc.perform(put("/api/admin/events/e-1/visibility")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("e-1"))
        .andExpect(jsonPath("$.visible").value(false));
  }

  @Test
  void updateEventVisibilityReturnsNotFoundForMissingId() throws Exception {
    String body = """
        {"visible": false}
        """;

    mockMvc.perform(put("/api/admin/events/nonexistent/visibility")
            .with(adminJwt().jwt(j -> j.subject("test-user")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isNotFound());
  }

  // --- Search and embedding sync ---

  @Test
  void triggerFullSearchSyncReturnsAccepted() throws Exception {
    mockMvc.perform(post("/api/admin/search/full-sync")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value("Full search index sync triggered"));
  }

  @Test
  void triggerFullSearchSyncRequiresAuth() throws Exception {
    mockMvc.perform(post("/api/admin/search/full-sync"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void triggerFullEmbeddingSyncReturnsAccepted() throws Exception {
    mockMvc.perform(post("/api/admin/embedding/full-sync")
            .with(adminJwt().jwt(j -> j.subject("test-user"))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value("Full embedding sync triggered"));
  }

  @Test
  void triggerFullEmbeddingSyncRequiresAuth() throws Exception {
    mockMvc.perform(post("/api/admin/embedding/full-sync"))
        .andExpect(status().isUnauthorized());
  }

  // --- Helpers ---

  private ContentSource sampleSource(final String id, final String name, final boolean active) {
    return sampleSource(id, name, active, null);
  }

  private ContentSource sampleSource(
      final String id, final String name, final boolean active,
      final String categoryFilter) {
    return new ContentSource(
        id,
        name,
        "https://example.com",
        "https://example.com/rss",
        null,
        ContentSource.SourceType.BLOG,
        ContentSource.ScrapeStrategy.RSS,
        active,
        null,
        null,
        categoryFilter);
  }

  private AggregatedArticle sampleArticle(
      final String id, final String title, final boolean visible) {
    return sampleArticle(id, title, visible, Instant.parse("2026-01-15T10:00:00Z"));
  }

  private AggregatedArticle sampleArticle(
      final String id, final String title, final boolean visible, final Instant publishedDate) {
    return new AggregatedArticle(
        id,
        title,
        "Tech Blog",
        "https://techblog.example.com",
        "https://techblog.example.com/articles/" + id,
        "A summary of the article",
        "Full article content here.",
        "Test Author",
        publishedDate,
        Instant.parse("2026-01-15T11:00:00Z"),
        visible,
        null);
  }

  private AggregatedEvent sampleEvent(
      final String id, final String title, final boolean visible) {
    return sampleEvent(id, title, visible, Instant.parse("2026-06-01T09:00:00Z"));
  }

  private AggregatedEvent sampleEvent(
      final String id, final String title, final boolean visible, final Instant eventDate) {
    return new AggregatedEvent(
        id,
        title,
        "Events Source",
        "https://events.example.com/events/" + id,
        "A summary of the event",
        "Full event description here.",
        eventDate,
        Instant.parse("2026-06-01T17:00:00Z"),
        "Convention Centre",
        "Sydney, Australia",
        Instant.parse("2026-01-15T11:00:00Z"),
        visible);
  }
}
