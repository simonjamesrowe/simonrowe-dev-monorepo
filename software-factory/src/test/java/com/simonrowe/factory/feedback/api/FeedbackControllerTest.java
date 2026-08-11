package com.simonrowe.factory.feedback.api;

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
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
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
class FeedbackControllerTest {

  private static final String TOKEN = "trigger-token";
  private static final String BODY =
      """
      {"owner":"example","repository":"project","pullNumber":42,"dryRun":false}
      """;

  private final FeedbackWorkflowService workflowService =
      Mockito.mock(FeedbackWorkflowService.class);
  private final GitHubCredentials credentials = Mockito.mock(GitHubCredentials.class);

  private MockMvc mockMvcWithToken(final String configuredToken) {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(configuredToken), "https://temporal.test");
    return MockMvcBuilders.standaloneSetup(
            new FeedbackController(properties, workflowService, credentials))
        .build();
  }

  @Test
  void startsFeedbackWhenTheTriggerTokenMatches() throws Exception {
    when(workflowService.start(any())).thenReturn(new FeedbackAccepted("workflow-1", true));

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/feedback")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(workflowService).start(any());
  }

  @Test
  void rejectsStartWithoutTheTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  @Test
  void rejectsStartWithTheWrongTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/feedback")
                .header("X-Factory-Token", "not-the-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  /**
   * Regression: a workflow id ineligible to (re)start (still running, or already completed) used
   * to return 202 with {@code started:false} and no visible signal that nothing happened.
   */
  @Test
  void returnsConflictWhenTheWorkflowCouldNotBeStarted() throws Exception {
    when(workflowService.start(any())).thenReturn(new FeedbackAccepted("workflow-1", false));

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/feedback")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isConflict());

    verify(workflowService).start(any());
  }

  @Test
  void returnsProgressWhenTheTriggerTokenMatches() throws Exception {
    when(workflowService.progress(anyString())).thenReturn(FeedbackProgress.accepted());

    mockMvcWithToken(TOKEN)
        .perform(get("/api/feedback/workflow-1").header("X-Factory-Token", TOKEN))
        .andExpect(status().isOk());

    verify(workflowService).progress("workflow-1");
  }

  /** Regression: the status endpoint used to be readable by anyone who could reach the port. */
  @Test
  void rejectsProgressLookupWithoutTheTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(get("/api/feedback/workflow-1"))
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).progress(anyString());
  }

  @Test
  void rejectsProgressLookupWithTheWrongTriggerToken() throws Exception {
    mockMvcWithToken(TOKEN)
        .perform(get("/api/feedback/workflow-1").header("X-Factory-Token", "not-the-token"))
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
            post("/api/feedback")
                .header("X-Factory-Token", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isServiceUnavailable());
    mockMvc
        .perform(get("/api/feedback/workflow-1").header("X-Factory-Token", ""))
        .andExpect(status().isServiceUnavailable());

    verify(workflowService, never()).start(any());
    verify(workflowService, never()).progress(anyString());
  }

  @Test
  void startsManualFeedbackAgainstTheResolvedAppInstallation() throws Exception {
    when(workflowService.start(any())).thenReturn(new FeedbackAccepted("workflow-1", true));
    when(credentials.installationId("example", "project")).thenReturn(4242L);

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/feedback")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted());

    ArgumentCaptor<FeedbackRequest> started = ArgumentCaptor.forClass(FeedbackRequest.class);
    verify(workflowService).start(started.capture());
    assertThat(started.getValue().installationId()).isEqualTo(4242L);
    assertThat(started.getValue().dryRun()).isFalse();
  }

  /**
   * Regression: a fixed {@code dryRun:false} body can't distinguish "propagated correctly"
   * from "hardcoded to false" in {@link FeedbackController#start}, so this pins the {@code
   * true} case too.
   */
  @Test
  void propagatesDryRunTrueFromTheRequestBody() throws Exception {
    when(workflowService.start(any())).thenReturn(new FeedbackAccepted("workflow-1", true));
    String dryRunBody =
        """
        {"owner":"example","repository":"project","pullNumber":42,"dryRun":true}
        """;

    mockMvcWithToken(TOKEN)
        .perform(
            post("/api/feedback")
                .header("X-Factory-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(dryRunBody))
        .andExpect(status().isAccepted());

    ArgumentCaptor<FeedbackRequest> started = ArgumentCaptor.forClass(FeedbackRequest.class);
    verify(workflowService).start(started.capture());
    assertThat(started.getValue().dryRun()).isTrue();
  }
}
