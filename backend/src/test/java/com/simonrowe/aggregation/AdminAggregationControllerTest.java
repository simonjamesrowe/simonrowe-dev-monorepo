package com.simonrowe.aggregation;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.search.IndexService;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

  // --- Admin news filters and sort ---

  @Test
  void listNewsMatchesFreeTextAcrossFieldsIncludingHiddenArticles() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "Spring Boot 4 released", "Spring Blog", true),
        article("a-2", "Hidden spring news", "Other", false),
        article("a-3", "Unrelated", "Claude Blog", true)));

    mockMvc.perform(get("/api/admin/news")
            .param("q", "SPRING")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].id", containsInAnyOrder("a-1", "a-2")));
  }

  @Test
  void listNewsFiltersToHiddenOnly() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "Visible", "Tech Blog", true),
        article("a-2", "Hidden", "Tech Blog", false)));

    mockMvc.perform(get("/api/admin/news")
            .param("visibility", "hidden")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-2"));
  }

  @Test
  void listNewsFiltersToVisibleOnly() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "Visible", "Tech Blog", true),
        article("a-2", "Hidden", "Tech Blog", false)));

    mockMvc.perform(get("/api/admin/news")
            .param("visibility", "VISIBLE")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  @Test
  void listNewsCombinesTextHiddenOnlyAndSourceAsymmetrically() throws Exception {
    // Each article fails exactly one of the three filters except a-1, so a filter that is
    // silently dropped lets its decoy through.
    articleRepository.saveAll(List.of(
        article("a-1", "Kafka tuning", "Spring Blog", false),
        article("a-2", "Kafka tuning", "Spring Blog", true),
        article("a-3", "Kafka tuning", "Claude Blog", false),
        article("a-4", "Mongo tuning", "Spring Blog", false)));

    mockMvc.perform(get("/api/admin/news")
            .param("q", "kafka")
            .param("visibility", "hidden")
            .param("source", "Spring Blog")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  @Test
  void listNewsReadsRepeatedSourcesWithoutSplittingOnCommas() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "One", "Smith, Jones & Co", true),
        article("a-2", "Two", "Claude Blog", false),
        article("a-3", "Three", "Spring Blog", true)));

    mockMvc.perform(get("/api/admin/news")
            .param("source", "Smith, Jones & Co")
            .param("source", "Claude Blog")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].id", containsInAnyOrder("a-1", "a-2")));

    mockMvc.perform(get("/api/admin/news")
            .param("source", "Smith, Jones & Co")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  @Test
  void listNewsSortsByTitleCaseInsensitivelyInEitherDirection() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "banana", "Tech Blog", true),
        article("a-2", "Apple", "Tech Blog", true),
        article("a-3", "Cherry", "Tech Blog", true)));

    mockMvc.perform(get("/api/admin/news")
            .param("sort", "title")
            .param("direction", "asc")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].title", contains("Apple", "banana", "Cherry")));

    mockMvc.perform(get("/api/admin/news")
            .param("sort", "title")
            .param("direction", "desc")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].title", contains("Cherry", "banana", "Apple")));
  }

  @Test
  void listNewsSortsByFetchedAtIndependentlyOfPublishedDate() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "Published first, fetched last", "Tech Blog", true,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
        article("a-2", "Published last, fetched first", "Tech Blog", true,
            Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))));

    mockMvc.perform(get("/api/admin/news")
            .param("sort", "fetchedAt")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", contains("a-1", "a-2")));
  }

  @Test
  void listNewsSortsBySourceName() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "One", "Zeta", true),
        article("a-2", "Two", "alpha", true)));

    mockMvc.perform(get("/api/admin/news")
            .param("sort", "sourceName")
            .param("direction", "asc")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", contains("a-2", "a-1")));
  }

  @Test
  void listNewsBreaksDateTiesByIdSoPagesNeverOverlap() throws Exception {
    Instant same = Instant.parse("2026-01-15T10:00:00Z");
    articleRepository.saveAll(List.of(
        sampleArticle("a-3", "Three", true, same),
        sampleArticle("a-1", "One", true, same),
        sampleArticle("a-2", "Two", true, same)));

    mockMvc.perform(get("/api/admin/news")
            .param("size", "2")
            .with(adminJwt()))
        .andExpect(jsonPath("$.content[*].id", contains("a-1", "a-2")));
    mockMvc.perform(get("/api/admin/news")
            .param("size", "2")
            .param("page", "1")
            .with(adminJwt()))
        .andExpect(jsonPath("$.content[*].id", contains("a-3")));
  }

  @Test
  void listNewsRefusesAnUnknownSortField() throws Exception {
    mockMvc.perform(get("/api/admin/news")
            .param("sort", "fullContent")
            .with(adminJwt()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            "sort must be one of fetchedAt, publishedDate, sourceName, title"));
  }

  @Test
  void listNewsRefusesAnUnknownDirection() throws Exception {
    mockMvc.perform(get("/api/admin/news")
            .param("direction", "sideways")
            .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listNewsRefusesAnUnknownVisibility() throws Exception {
    mockMvc.perform(get("/api/admin/news")
            .param("visibility", "maybe")
            .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listNewsClampsAnOversizedPage() throws Exception {
    mockMvc.perform(get("/api/admin/news")
            .param("size", "100000")
            .param("page", "-3")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(AdminAggregationController.MAX_PAGE_SIZE))
        .andExpect(jsonPath("$.number").value(0));
  }

  @Test
  void listArticleSourcesCountsHiddenArticlesToo() throws Exception {
    articleRepository.saveAll(List.of(
        article("a-1", "One", "Spring Blog", true),
        article("a-2", "Two", "Spring Blog", false),
        article("a-3", "Three", "All Hidden", false)));

    mockMvc.perform(get("/api/admin/news/sources").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("Spring Blog"))
        .andExpect(jsonPath("$[0].count").value(2))
        .andExpect(jsonPath("$[1].name").value("All Hidden"))
        .andExpect(jsonPath("$[1].count").value(1));
  }

  @Test
  void listArticleSourcesRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/admin/news/sources"))
        .andExpect(status().isUnauthorized());
  }

  // --- Admin event filters and sort ---

  @Test
  void listEventsMatchesFreeTextOnVenueAndLocation() throws Exception {
    eventRepository.saveAll(List.of(
        event("e-1", "Meetup", "Meetup", true, "ExCeL", "London"),
        event("e-2", "Conference", "lu.ma", false, "Olympia", "Kensington, London"),
        event("e-3", "Summit", "lu.ma", true, "Hall", "Paris")));

    mockMvc.perform(get("/api/admin/events")
            .param("q", "london")
            .with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].id", containsInAnyOrder("e-1", "e-2")));

    mockMvc.perform(get("/api/admin/events")
            .param("q", "excel")
            .with(adminJwt()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"));
  }

  @Test
  void listEventsCombinesTextWithVisibleOnly() throws Exception {
    eventRepository.saveAll(List.of(
        event("e-1", "London meetup", "Meetup", true, "Hall", "London"),
        event("e-2", "London summit", "Meetup", false, "Hall", "London")));

    mockMvc.perform(get("/api/admin/events")
            .param("q", "london")
            .param("visibility", "visible")
            .with(adminJwt()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"));
  }

  @Test
  void listEventsSortsByTitleAscending() throws Exception {
    eventRepository.saveAll(List.of(
        event("e-1", "zeta", "Meetup", true, "Hall", "London"),
        event("e-2", "Alpha", "Meetup", true, "Hall", "London")));

    mockMvc.perform(get("/api/admin/events")
            .param("sort", "title")
            .param("direction", "asc")
            .with(adminJwt()))
        .andExpect(jsonPath("$.content[*].id", contains("e-2", "e-1")));
  }

  @Test
  void listEventsRefusesAnArticleOnlySortField() throws Exception {
    mockMvc.perform(get("/api/admin/events")
            .param("sort", "publishedDate")
            .with(adminJwt()))
        .andExpect(status().isBadRequest());
  }

  // --- Index notifications ---

  @Test
  void hidingAnArticlePublishesAnUpdate() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Visible Article", true));

    mockMvc.perform(put("/api/admin/news/a-1/visibility")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"visible": false}
                """))
        .andExpect(status().isOk());

    verify(contentChangePublisher).publishUpdated(ContentType.AGGREGATED_ARTICLE, "a-1");
  }

  @Test
  void deletingAnArticlePublishesDeleted() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Article", true));

    mockMvc.perform(delete("/api/admin/news/a-1").with(adminJwt()))
        .andExpect(status().isNoContent());

    verify(contentChangePublisher).publishDeleted(ContentType.AGGREGATED_ARTICLE, "a-1");
    assertThat(articleRepository.findById("a-1")).isEmpty();
  }

  @Test
  void hidingAndDeletingAnEventPublishBothEvents() throws Exception {
    eventRepository.save(sampleEvent("e-1", "Event", true));

    mockMvc.perform(put("/api/admin/events/e-1/visibility")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"visible": false}
                """))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/admin/events/e-1").with(adminJwt()))
        .andExpect(status().isNoContent());

    verify(contentChangePublisher).publishUpdated(ContentType.AGGREGATED_EVENT, "e-1");
    verify(contentChangePublisher).publishDeleted(ContentType.AGGREGATED_EVENT, "e-1");
  }

  @Test
  void publishFailureDoesNotFailTheAdminRequest() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Article", true));
    articleRepository.save(sampleArticle("a-2", "Article", true));
    doThrow(new IllegalStateException("broker down"))
        .when(contentChangePublisher).publishUpdated(any(), anyString());
    doThrow(new IllegalStateException("broker down"))
        .when(contentChangePublisher).publishDeleted(any(), anyString());

    mockMvc.perform(put("/api/admin/news/a-1/visibility")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"visible": false}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visible").value(false));
    mockMvc.perform(delete("/api/admin/news/a-2").with(adminJwt()))
        .andExpect(status().isNoContent());

    assertThat(articleRepository.findById("a-1")).get()
        .extracting(AggregatedArticle::visible).isEqualTo(false);
    assertThat(articleRepository.findById("a-2")).isEmpty();
  }

  // --- Bulk actions ---

  @Test
  void bulkHideHidesFoundArticlesAndReportsUnknownIds() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "One", true),
        sampleArticle("a-2", "Two", true),
        sampleArticle("a-3", "Untouched", true)));

    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["a-1", "a-2", "a-1", "missing"], "action": "hide"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("hide"))
        .andExpect(jsonPath("$.requested").value(3))
        .andExpect(jsonPath("$.updated").value(2))
        .andExpect(jsonPath("$.notFound").value(1))
        .andExpect(jsonPath("$.notFoundIds[0]").value("missing"));

    assertThat(articleRepository.findById("a-1")).get()
        .extracting(AggregatedArticle::visible).isEqualTo(false);
    assertThat(articleRepository.findById("a-2")).get()
        .extracting(AggregatedArticle::visible).isEqualTo(false);
    assertThat(articleRepository.findById("a-3")).get()
        .extracting(AggregatedArticle::visible).isEqualTo(true);
    verify(contentChangePublisher).publishUpdated(ContentType.AGGREGATED_ARTICLE, "a-1");
    verify(contentChangePublisher).publishUpdated(ContentType.AGGREGATED_ARTICLE, "a-2");
    verify(contentChangePublisher, never())
        .publishUpdated(ContentType.AGGREGATED_ARTICLE, "missing");
    verify(contentChangePublisher, never())
        .publishUpdated(ContentType.AGGREGATED_ARTICLE, "a-3");
  }

  @Test
  void bulkShowShowsHiddenArticles() throws Exception {
    articleRepository.save(sampleArticle("a-1", "One", false));

    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["a-1"], "action": "show"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(1))
        .andExpect(jsonPath("$.notFound").value(0));

    assertThat(articleRepository.findById("a-1")).get()
        .extracting(AggregatedArticle::visible).isEqualTo(true);
  }

  @Test
  void bulkDeleteRemovesArticlesAndPublishesOnlyForThoseFound() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticle("a-1", "One", true),
        sampleArticle("a-2", "Two", false)));

    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["a-1", "a-2", "gone"], "action": "delete"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(2))
        .andExpect(jsonPath("$.notFound").value(1));

    assertThat(articleRepository.count()).isZero();
    verify(contentChangePublisher).publishDeleted(ContentType.AGGREGATED_ARTICLE, "a-1");
    verify(contentChangePublisher).publishDeleted(ContentType.AGGREGATED_ARTICLE, "a-2");
    verify(contentChangePublisher, never())
        .publishDeleted(ContentType.AGGREGATED_ARTICLE, "gone");
  }

  @Test
  void bulkDeleteWorksForEventsToo() throws Exception {
    eventRepository.saveAll(List.of(
        sampleEvent("e-1", "One", true),
        sampleEvent("e-2", "Two", true)));

    mockMvc.perform(post("/api/admin/events/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["e-1"], "action": "delete"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(1));

    assertThat(eventRepository.findById("e-1")).isEmpty();
    assertThat(eventRepository.findById("e-2")).isPresent();
    verify(contentChangePublisher).publishDeleted(ContentType.AGGREGATED_EVENT, "e-1");
  }

  @Test
  void bulkHideStillSucceedsWhenPublishingFails() throws Exception {
    eventRepository.save(sampleEvent("e-1", "One", true));
    doThrow(new IllegalStateException("broker down"))
        .when(contentChangePublisher).publishUpdated(any(), anyString());

    mockMvc.perform(post("/api/admin/events/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["e-1"], "action": "hide"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(1));

    assertThat(eventRepository.findById("e-1")).get()
        .extracting(AggregatedEvent::visible).isEqualTo(false);
  }

  @Test
  void bulkRefusesMoreThanTheCap() throws Exception {
    String ids = IntStream.rangeClosed(0, AggregatedContentAdminService.MAX_BULK_IDS)
        .mapToObj(i -> "\"id-" + i + "\"")
        .collect(Collectors.joining(","));

    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": [%s], "action": "hide"}
                """.formatted(ids)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("ids may name at most 200 items"));
  }

  @Test
  void bulkRefusesAnEmptyIdList() throws Exception {
    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": [], "action": "hide"}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkRefusesAnUnknownAction() throws Exception {
    articleRepository.save(sampleArticle("a-1", "One", true));

    mockMvc.perform(post("/api/admin/news/bulk")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["a-1"], "action": "archive"}
                """))
        .andExpect(status().isBadRequest());

    assertThat(articleRepository.findById("a-1")).isPresent();
  }

  @Test
  void bulkRequiresAuth() throws Exception {
    mockMvc.perform(post("/api/admin/news/bulk")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"ids": ["a-1"], "action": "delete"}
                """))
        .andExpect(status().isUnauthorized());
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

  private AggregatedArticle article(
      final String id, final String title, final String source, final boolean visible) {
    return article(id, title, source, visible,
        Instant.parse("2026-01-15T10:00:00Z"), Instant.parse("2026-01-15T11:00:00Z"));
  }

  private AggregatedArticle article(
      final String id, final String title, final String source, final boolean visible,
      final Instant publishedDate, final Instant fetchedAt) {
    return new AggregatedArticle(
        id, title, source, "https://example.com", "https://example.com/articles/" + id,
        "A summary", "Full content", "Author", publishedDate, fetchedAt, visible, null);
  }

  private AggregatedEvent event(
      final String id, final String title, final String source, final boolean visible,
      final String venue, final String location) {
    return new AggregatedEvent(
        id, title, source, "https://events.example.com/" + id, "A summary",
        "Description", Instant.parse("2026-06-01T09:00:00Z"), null, venue, location,
        Instant.parse("2026-01-15T11:00:00Z"), visible);
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
