package com.simonrowe.factoryadmin;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The browser-facing contract. Role protection is asserted in {@code SecurityConfigTest} against
 * the real filter chain; this covers the shapes and the request validation.
 */
class FactoryAdminControllerTest {

  private static final String BASE = "/api/admin/software-factory";
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  private final FactoryAdminService service = mock(FactoryAdminService.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new FactoryAdminController(service)).build();

  @Test
  void servesTheAggregatedStatus() throws Exception {
    when(service.status()).thenReturn(
        new FactoryAdminStatus(Instant.EPOCH, SHA, true, false, List.of()));

    mockMvc.perform(get(BASE + "/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.backendCommit").value(SHA))
        .andExpect(jsonPath("$.factoryReachable").value(true))
        .andExpect(jsonPath("$.deployerReachable").value(false));
  }

  @Test
  void acceptsCodeReviewForPullRequest() throws Exception {
    when(service.startCodeReview(130, true))
        .thenReturn(new FactoryRunAccepted("code-review-130-uuid", null, "accepted"));

    mockMvc.perform(
            post(BASE + "/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pullNumber\":130,\"publish\":true}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("code-review-130-uuid"));
  }

  @Test
  void defaultsAnOmittedPublishFlagToDryRun() throws Exception {
    // Safer default: an absent flag reviews and posts nothing, rather than commenting publicly
    // on a pull request because a field was left out of the request.
    when(service.startCodeReview(anyInt(), anyBoolean()))
        .thenReturn(new FactoryRunAccepted("code-review-130-uuid", null, "accepted"));

    mockMvc.perform(
            post(BASE + "/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pullNumber\":130}"))
        .andExpect(status().isAccepted());

    verify(service).startCodeReview(130, false);
  }

  @Test
  void rejectsCodeReviewWithNoPullRequest() throws Exception {
    mockMvc.perform(
            post(BASE + "/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pullNumber\":0,\"publish\":true}"))
        .andExpect(status().isBadRequest());

    verify(service, never()).startCodeReview(anyInt(), anyBoolean());
  }

  @Test
  void acceptsVulnerabilityScan() throws Exception {
    when(service.startVulnerabilityScan())
        .thenReturn(new FactoryRunAccepted("cve-scan-manual-1", "run-1", "accepted"));

    mockMvc.perform(post(BASE + "/vulnerability-scans"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("cve-scan-manual-1"));
  }

  @Test
  void acceptsFeedbackRunForPullRequest() throws Exception {
    when(service.startFeedback(42))
        .thenReturn(new FactoryRunAccepted("review-feedback-42", null, "accepted"));

    mockMvc.perform(
            post(BASE + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pullNumber\":42}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("review-feedback-42"));
  }

  @Test
  void rejectsFeedbackRunWithNoPullRequest() throws Exception {
    mockMvc.perform(
            post(BASE + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pullNumber\":0}"))
        .andExpect(status().isBadRequest());

    verify(service, never()).startFeedback(anyInt());
  }

  @Test
  void carriesTheBackupModeThrough() throws Exception {
    when(service.startPlatformBackup(anyBoolean()))
        .thenReturn(new FactoryRunAccepted("platform-backup-manual", "run-2", "accepted"));

    mockMvc.perform(
            post(BASE + "/platform-backups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isAccepted());

    verify(service).startPlatformBackup(true);
  }

  @Test
  void requiresBothTheCommitAndTheConfirmationForDeploy() throws Exception {
    mockMvc.perform(
            post(BASE + "/deploys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"frontendCommit\":\"" + SHA + "\",\"confirmation\":\"\"}"))
        .andExpect(status().isBadRequest());

    verify(service, never()).startDeploy(anyString(), anyString());
  }

  @Test
  void acceptsConfirmedDeploy() throws Exception {
    when(service.startDeploy(SHA, "REDEPLOY 0123456"))
        .thenReturn(new FactoryRunAccepted("deploy-prod", "run-1", "Redeploying 0123456"));

    mockMvc.perform(
            post(BASE + "/deploys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"frontendCommit\":\"" + SHA
                    + "\",\"confirmation\":\"REDEPLOY 0123456\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.detail").value("Redeploying 0123456"));
  }

  @Test
  void servesRunProgress() throws Exception {
    when(service.progress("cve-scan-manual-1"))
        .thenReturn(new FactoryRunProgress(
            "cve-scan-manual-1", "run-1", "WORKFLOW_EXECUTION_STATUS_RUNNING", "FILING",
            "Filing in Linear", false));

    mockMvc.perform(get(BASE + "/runs/cve-scan-manual-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("FILING"))
        .andExpect(jsonPath("$.terminal").value(false));
  }
}
