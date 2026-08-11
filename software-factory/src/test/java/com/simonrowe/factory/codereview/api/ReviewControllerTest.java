package com.simonrowe.factory.codereview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ReviewProgress;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Neither endpoint is routed by nginx, but this process also terminates the public webhook, so the
 * token check — not the proxy configuration — is what keeps them closed.
 */
class ReviewControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String BODY =
      """
      {"owner":"example","repository":"project","pullNumber":42,"publish":false}
      """;

  private final ReviewWorkflowService workflowService = Mockito.mock(ReviewWorkflowService.class);
  private final GitHubCredentials credentials = Mockito.mock(GitHubCredentials.class);

  private MockMvc mockMvcWithToken(final String configuredToken) {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(configuredToken), "https://temporal.test");
    return MockMvcBuilders.standaloneSetup(
            new ReviewController(properties, workflowService, credentials))
        .build();
  }

  @Test
  void startsReviewWhenTheTriggerTokenMatches() throws Exception {
    when(workflowService.start(any())).thenReturn(new ReviewAccepted("workflow-1", true));

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/reviews")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(workflowService).start(any());
  }

  @Test
  void rejectsStartWithoutTheTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  @Test
  void rejectsStartWithTheWrongTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/reviews")
                .header("X-Factory-Token", "not-the-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  @Test
  void returnsProgressWhenTheTriggerTokenMatches() throws Exception {
    when(workflowService.progress(anyString())).thenReturn(ReviewProgress.accepted());

    mockMvcWithToken(TOKEN)
        .perform(get("/api/reviews/workflow-1").header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk());

    verify(workflowService).progress("workflow-1");
  }

  /** Regression: the status endpoint used to be readable by anyone who could reach the port. */
  @Test
  void rejectsProgressLookupWithoutTheTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(get("/api/reviews/workflow-1"))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).progress(anyString());
  }

  @Test
  void rejectsProgressLookupWithTheWrongTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(get("/api/reviews/workflow-1").header("X-Factory-Token", "not-the-token"))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).progress(anyString());
  }

  /**
   * With no token configured the endpoints must fail closed rather than accept every caller, since
   * an unset {@code FACTORY_TRIGGER_TOKEN} resolves to the empty string.
   */
  @Test
  void disablesBothEndpointsWhenNoTriggerTokenIsConfigured() throws Exception {
    MockMvc mockMvc = mockMvcWithToken("");

    mockMvc
        .perform(
            post("/api/reviews")
                .header("X-Factory-Token", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isServiceUnavailable());
    mockMvc
        .perform(get("/api/reviews/workflow-1").header("X-Factory-Token", ""))
        .andExpect(status().isServiceUnavailable());

    verify(workflowService, never()).start(any());
    verify(workflowService, never()).progress(anyString());
  }

  /**
   * Regression: this endpoint used to hardcode a null installation id, so the dry run documented as
   * the pre-deploy check cloned anonymously and exercised no credential at all. A bearer-token
   * clone bug passed that check and then failed every webhook-triggered review.
   */
  @Test
  void startsManualReviewsAgainstTheResolvedAppInstallation() throws Exception {
    when(workflowService.start(any())).thenReturn(new ReviewAccepted("workflow-1", true));
    when(credentials.installationId("example", "project")).thenReturn(4242L);

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/reviews")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ReviewRequest> started = ArgumentCaptor.forClass(ReviewRequest.class);
    verify(workflowService).start(started.capture());
    assertThat(started.getValue().installationId()).isEqualTo(4242L);
  }
}
