package com.simonrowe.summary;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.narration.NarrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * Status codes and delegation. The response body is the existing {@code NarrationResponse}
 * contract verbatim, so the frontend polling logic is shared with blog narration.
 */
class SummaryNarrationControllerTest {

  private NarrationService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(NarrationService.class);
    mockMvc = MockMvcBuilders
        .standaloneSetup(new SummaryNarrationController(service))
        .build();
  }

  @Test
  void postReturnsAcceptedWhenNewWorkIsQueued() throws Exception {
    when(service.request(NarrationContentType.ARTICLE_SUMMARY, "art-1"))
        .thenReturn(new NarrationService.RequestResult(new NarrationResponse(
            NarrationResponse.PublicState.QUEUED, 1, null, null, false,
            "Preparing audio"), true));

    mockMvc.perform(post("/api/news/art-1/summary/narration"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"));
  }

  @Test
  void postReturnsOkWhenAnExistingNarrationIsReused() throws Exception {
    when(service.request(NarrationContentType.ARTICLE_SUMMARY, "art-1"))
        .thenReturn(new NarrationService.RequestResult(new NarrationResponse(
            NarrationResponse.PublicState.READY, 4,
            "/uploads/narrations/id/narration.mp3", 90L, false, "Ready"), false));

    mockMvc.perform(post("/api/news/art-1/summary/narration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.audioUrl").value("/uploads/narrations/id/narration.mp3"))
        .andExpect(jsonPath("$.durationSeconds").value(90));
  }

  /**
   * A 503 is part of the contract, not a generic failure: it carries an
   * {@code UNAVAILABLE} response the drawer renders as "audio is temporarily unavailable"
   * while the written summary keeps working.
   */
  @Test
  void postReturnsServiceUnavailableCarryingAnUnavailableResponse() throws Exception {
    when(service.request(NarrationContentType.ARTICLE_SUMMARY, "art-1"))
        .thenReturn(new NarrationService.RequestResult(
            NarrationResponse.unavailable(), false));

    mockMvc.perform(post("/api/news/art-1/summary/narration"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.state").value("UNAVAILABLE"));
  }

  @Test
  void postReturnsNotFoundWhenTheArticleHasNoReadySummaryToNarrate() throws Exception {
    when(service.request(NarrationContentType.ARTICLE_SUMMARY, "art-1"))
        .thenThrow(new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No summary to narrate for this article"));

    mockMvc.perform(post("/api/news/art-1/summary/narration"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getDelegatesLongPollParametersWithTheArticleSummaryContentType() throws Exception {
    when(service.getStatus(NarrationContentType.ARTICLE_SUMMARY, "art-1", 3L, 25))
        .thenReturn(NarrationResponse.notRequested());

    mockMvc.perform(get("/api/news/art-1/summary/narration")
            .param("afterVersion", "3")
            .param("waitSeconds", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NOT_REQUESTED"));

    verify(service).getStatus(
        eq(NarrationContentType.ARTICLE_SUMMARY), eq("art-1"), eq(3L), eq(25));
  }

  @Test
  void getDefaultsToAnImmediateReadWhenNoLongPollIsAskedFor() throws Exception {
    when(service.getStatus(NarrationContentType.ARTICLE_SUMMARY, "art-1", null, 0))
        .thenReturn(NarrationResponse.notRequested());

    mockMvc.perform(get("/api/news/art-1/summary/narration"))
        .andExpect(status().isOk());

    verify(service).getStatus(
        eq(NarrationContentType.ARTICLE_SUMMARY), eq("art-1"), eq(null), eq(0));
  }

  @Test
  void internalFieldsAreNotLeakedOnTheWire() throws Exception {
    when(service.getStatus(NarrationContentType.ARTICLE_SUMMARY, "art-1", null, 0))
        .thenReturn(new NarrationResponse(
            NarrationResponse.PublicState.READY, 4,
            "/uploads/narrations/id/narration.mp3", 90L, false, "Ready"));

    mockMvc.perform(get("/api/news/art-1/summary/narration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerOperationName").doesNotExist())
        .andExpect(jsonPath("$.leaseUntil").doesNotExist())
        .andExpect(jsonPath("$.contentId").doesNotExist());
  }
}
