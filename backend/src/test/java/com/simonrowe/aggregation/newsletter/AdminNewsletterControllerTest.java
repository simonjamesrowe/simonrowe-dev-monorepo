package com.simonrowe.aggregation.newsletter;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.scrapers.ScrapedContent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AdminNewsletterControllerTest extends AbstractIntegrationTest {

  @MockitoBean
  private ContentAggregationAgent aggregationAgent;

  @MockitoBean
  private NewsletterIngestService ingestService;

  @Autowired
  private NewsletterCandidateRepository candidateRepository;

  @Autowired
  private ContentSourceRepository sourceRepository;

  @AfterEach
  void tearDown() {
    candidateRepository.deleteAll();
    sourceRepository.deleteAll();
  }

  private static NewsletterCandidate candidate(
      final String id, final String source, final double relevance, final Status status,
      final Instant receivedAt) {
    return new NewsletterCandidate(id, source, "Story " + id, "https://example.com/" + id,
        "Summary", "Section", "2 minute read", "m-" + id, "Issue", receivedAt, relevance,
        "Hearted", status, "reason", null, Instant.now(), Instant.now());
  }

  @Test
  void requiresTheAdminRole() throws Exception {
    mockMvc.perform(get("/api/admin/newsletter-candidates")).andExpect(status().isUnauthorized());
  }

  @Test
  void listsTheQueueBestFirstAndFiltersBySource() throws Exception {
    Instant now = Instant.parse("2026-10-06T12:00:00Z");
    candidateRepository.saveAll(List.of(
        candidate("a", "TLDR", 0.31, Status.PENDING, now),
        candidate("b", "TLDR Dev", 0.47, Status.PENDING, now.minusSeconds(60)),
        candidate("c", "TLDR", 0.62, Status.ACCEPTED, now)));

    mockMvc.perform(get("/api/admin/newsletter-candidates").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].id").value("b"))
        .andExpect(jsonPath("$.content[1].id").value("a"));

    mockMvc.perform(get("/api/admin/newsletter-candidates")
            .param("source", "TLDR").param("sort", "received").with(adminJwt()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a"));

    mockMvc.perform(get("/api/admin/newsletter-candidates")
            .param("status", "ACCEPTED").with(adminJwt()))
        .andExpect(jsonPath("$.content[0].id").value("c"));
  }

  @Test
  void summaryCountsEachStatusAndReportsTheThreshold() throws Exception {
    Instant now = Instant.now();
    candidateRepository.saveAll(List.of(
        candidate("a", "TLDR", 0.3, Status.PENDING, now),
        candidate("b", "TLDR", 0.3, Status.PENDING, now),
        candidate("c", "TLDR", 0.7, Status.ACCEPTED, now)));

    mockMvc.perform(get("/api/admin/newsletter-candidates/summary").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counts.PENDING").value(2))
        .andExpect(jsonPath("$.counts.ACCEPTED").value(1))
        .andExpect(jsonPath("$.counts.DISMISSED").value(0))
        .andExpect(jsonPath("$.relevanceThreshold").value(0.5));
  }

  @Test
  void promoteSavesAndDismissRecordsTheDecision() throws Exception {
    sourceRepository.save(new ContentSource(null, "TLDR", "https://tldr.tech",
        "dan@tldrnewsletter.com", null, ContentSource.SourceType.NEWS,
        ContentSource.ScrapeStrategy.EMAIL_NEWSLETTER, true, null, null, null));
    candidateRepository.saveAll(List.of(
        candidate("a", "TLDR", 0.3, Status.PENDING, Instant.now()),
        candidate("b", "TLDR", 0.3, Status.PENDING, Instant.now())));
    ScrapedContent content = new ScrapedContent("Story a", "https://example.com/a", "Body",
        Instant.now(), null, null, false);
    when(ingestService.toContent(any())).thenReturn(content);
    when(aggregationAgent.saveCuratedArticle(any(), eq(content), eq("Summary")))
        .thenReturn(Optional.of("article-1"));

    mockMvc.perform(post("/api/admin/newsletter-candidates/a/promote").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROMOTED"))
        .andExpect(jsonPath("$.articleId").value("article-1"));

    mockMvc.perform(post("/api/admin/newsletter-candidates/b/dismiss").with(adminJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISMISSED"));

    // Promoting again is a conflict, not a second article.
    mockMvc.perform(post("/api/admin/newsletter-candidates/a/promote").with(adminJwt()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("This story is already on the site"));

    mockMvc.perform(post("/api/admin/newsletter-candidates/missing/dismiss").with(adminJwt()))
        .andExpect(status().isNotFound());
  }
}
