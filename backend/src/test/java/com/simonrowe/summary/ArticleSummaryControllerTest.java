package com.simonrowe.summary;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Status codes and long-poll parameter bounds. The service is mocked; its behaviour is
 * covered by {@code ArticleSummaryServiceTest} and {@code ArticleSummaryConcurrencyTest}.
 */
class ArticleSummaryControllerTest {

  private ArticleSummaryService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(ArticleSummaryService.class);
    mockMvc = MockMvcBuilders
        .standaloneSetup(new ArticleSummaryController(service))
        .build();
  }

  @Test
  void postReturnsOkWithTheProseWhenGenerationCompletes() throws Exception {
    when(service.request("art-1")).thenReturn(new ArticleSummaryService.RequestResult(
        new ArticleSummaryResponse(
            ArticleSummaryResponse.PublicState.READY, 2, "Paragraph one.",
            "test-model", Instant.parse("2026-08-24T10:31:07Z"), null, false,
            "Summary ready"),
        false));

    mockMvc.perform(post("/api/news/art-1/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.body").value("Paragraph one."))
        .andExpect(jsonPath("$.model").value("test-model"));
  }

  /** Losing the insert-first race is a 202, not an error: the client polls the GET. */
  @Test
  void postReturnsAcceptedWhenAnotherCallerIsAlreadyGenerating() throws Exception {
    when(service.request("art-1")).thenReturn(new ArticleSummaryService.RequestResult(
        new ArticleSummaryResponse(
            ArticleSummaryResponse.PublicState.GENERATING, 1, null, null, null,
            null, false, "Writing the summary"),
        true));

    mockMvc.perform(post("/api/news/art-1/summary"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("GENERATING"));
  }

  @Test
  void postReturnsOkForNonRetryableFailureRatherThanAnErrorStatus() throws Exception {
    when(service.request("art-1")).thenReturn(new ArticleSummaryService.RequestResult(
        new ArticleSummaryResponse(
            ArticleSummaryResponse.PublicState.FAILED, 2, null, null, null,
            ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false,
            "There is not enough of this article available to summarise."),
        false));

    mockMvc.perform(post("/api/news/art-1/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_SOURCE_TEXT"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  /**
   * The article exists but the summary does not, so this is a 200 carrying
   * NOT_REQUESTED — not a 404. A 404 would tell the client the article is gone.
   */
  @Test
  void getReturnsNotRequestedWhenNoSummaryExists() throws Exception {
    when(service.getStatus("art-1", null, 0))
        .thenReturn(ArticleSummaryResponse.notRequested());

    mockMvc.perform(get("/api/news/art-1/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.body").doesNotExist());
  }

  @Test
  void getReturnsNotFoundForMissingArticle() throws Exception {
    when(service.getStatus("missing", null, 0))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found"));

    mockMvc.perform(get("/api/news/missing/summary"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getPassesLongPollParametersThroughToTheService() throws Exception {
    when(service.getStatus("art-1", 3L, 25))
        .thenReturn(ArticleSummaryResponse.notRequested());

    mockMvc.perform(get("/api/news/art-1/summary")
            .param("afterVersion", "3")
            .param("waitSeconds", "25"))
        .andExpect(status().isOk());

    verify(service).getStatus(eq("art-1"), eq(3L), eq(25));
  }

  @Test
  void internalFieldsAreNotLeakedOnTheWire() throws Exception {
    when(service.getStatus("art-1", null, 0)).thenReturn(new ArticleSummaryResponse(
        ArticleSummaryResponse.PublicState.READY, 2, "Paragraph one.", "test-model",
        Instant.parse("2026-08-24T10:31:07Z"), null, false, "Summary ready"));

    mockMvc.perform(get("/api/news/art-1/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceCharacterCount").doesNotExist())
        .andExpect(jsonPath("$.requestedAt").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").doesNotExist())
        .andExpect(jsonPath("$.articleId").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist());
  }
}
