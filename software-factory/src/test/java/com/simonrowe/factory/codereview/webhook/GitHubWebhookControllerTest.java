package com.simonrowe.factory.codereview.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.api.ReviewAccepted;
import com.simonrowe.factory.codereview.api.ReviewWorkflowService;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The webhook is the only endpoint reachable from the internet, so these cases pin the behaviour
 * the whole deployment leans on: nothing starts a workflow without a signature that verifies
 * against the shared secret.
 */
class GitHubWebhookControllerTest {

  private static final String SECRET = "webhook-secret";

  private final ReviewWorkflowService workflowService = Mockito.mock(ReviewWorkflowService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", SECRET, "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api("trigger-token"));
    GitHubWebhookController controller =
        new GitHubWebhookController(
            properties, new WebhookSignatureVerifier(), workflowService, new ObjectMapper());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    when(workflowService.start(any())).thenReturn(new ReviewAccepted("workflow-1", true));
  }

  private static String sign(final String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256="
        + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static String pullRequestPayload(final String action, final boolean draft) {
    return """
        {
          "action": "%s",
          "pull_request": {
            "number": 42,
            "draft": %s,
            "head": {"sha": "0123456789abcdef0123456789abcdef01234567"}
          },
          "repository": {"name": "project", "owner": {"login": "example"}},
          "installation": {"id": 999}
        }
        """
        .formatted(action, draft);
  }

  private ResultActions deliver(
      final String payload, final String signature, final String event) throws Exception {
    var request =
        post("/webhooks/github").contentType(MediaType.APPLICATION_JSON).content(payload);
    if (signature != null) {
      request = request.header("X-Hub-Signature-256", signature);
    }
    if (event != null) {
      request = request.header("X-GitHub-Event", event);
    }
    return mockMvc.perform(request);
  }

  @Test
  void startsWorkflowForSignedPullRequestEvent() throws Exception {
    String payload = pullRequestPayload("opened", false);

    deliver(payload, sign(payload), "pull_request")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("workflow-1"));

    ArgumentCaptor<ReviewRequest> captor = ArgumentCaptor.forClass(ReviewRequest.class);
    verify(workflowService).start(captor.capture());
    ReviewRequest started = captor.getValue();
    assertThat(started.owner()).isEqualTo("example");
    assertThat(started.repository()).isEqualTo("project");
    assertThat(started.pullNumber()).isEqualTo(42);
    assertThat(started.installationId()).isEqualTo(999L);
  }

  @Test
  void rejectsAnUnsignedDelivery() throws Exception {
    String payload = pullRequestPayload("opened", false);

    deliver(payload, null, "pull_request").andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  @Test
  void rejectsDeliverySignedWithTheWrongSecret() throws Exception {
    String payload = pullRequestPayload("opened", false);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("wrong-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String forged =
        "sha256="
            + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

    deliver(payload, forged, "pull_request").andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  /** A signature is only valid for the exact bytes it was computed over. */
  @Test
  void rejectsBodySwappedAfterSigning() throws Exception {
    String signature = sign(pullRequestPayload("opened", false));

    deliver(pullRequestPayload("synchronize", false), signature, "pull_request")
        .andExpect(status().isUnauthorized());

    verify(workflowService, never()).start(any());
  }

  @Test
  void ignoresEventTypesOtherThanPullRequest() throws Exception {
    String payload = pullRequestPayload("opened", false);

    deliver(payload, sign(payload), "push")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ignored"));

    verify(workflowService, never()).start(any());
  }

  @Test
  void ignoresPullRequestActionsThatDoNotChangeTheHead() throws Exception {
    String payload = pullRequestPayload("closed", false);

    deliver(payload, sign(payload), "pull_request")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ignored"));

    verify(workflowService, never()).start(any());
  }

  @Test
  void ignoresDraftPullRequestsUntilTheyAreMarkedReady() throws Exception {
    String draft = pullRequestPayload("opened", true);

    deliver(draft, sign(draft), "pull_request")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ignored"));
    verify(workflowService, never()).start(any());

    String ready = pullRequestPayload("ready_for_review", true);
    deliver(ready, sign(ready), "pull_request").andExpect(status().isAccepted());
    verify(workflowService).start(any());
  }

  @Test
  void rejectsSignedBodyThatIsNotJson() throws Exception {
    String payload = "not json at all";

    deliver(payload, sign(payload), "pull_request")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("malformed"));

    verify(workflowService, never()).start(any());
  }

  @Test
  void rejectsSignedPullRequestEventMissingRepositoryCoordinates() throws Exception {
    String payload =
        """
        {
          "action": "opened",
          "pull_request": {"number": 42, "head": {"sha": ""}},
          "repository": {"name": "", "owner": {"login": ""}}
        }
        """;

    deliver(payload, sign(payload), "pull_request")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("malformed"));

    verify(workflowService, never()).start(any());
  }
}
