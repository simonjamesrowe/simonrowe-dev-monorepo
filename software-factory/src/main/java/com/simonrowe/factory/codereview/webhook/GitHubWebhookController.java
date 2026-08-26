package com.simonrowe.factory.codereview.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.api.ReviewAccepted;
import com.simonrowe.factory.codereview.api.ReviewWorkflowService;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.deploy.api.DeployAccepted;
import com.simonrowe.factory.deploy.api.DeployWorkflowService;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.feedback.api.FeedbackAccepted;
import com.simonrowe.factory.feedback.api.FeedbackWorkflowService;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Converts signed pull-request lifecycle events into idempotent Temporal workflows. */
@RestController
@RequestMapping("/webhooks/github")
public class GitHubWebhookController {

  private static final Set<String> ACTIONS =
      Set.of("opened", "reopened", "synchronize", "ready_for_review");

  private final CodeReviewProperties properties;
  private final WebhookSignatureVerifier signatureVerifier;
  private final ReviewWorkflowService workflowService;
  private final ObjectMapper objectMapper;
  private final FeedbackWorkflowService feedbackWorkflowService;
  private final FeedbackProperties feedbackProperties;
  private final DeployProperties deployProperties;

  /**
   * An {@link ObjectProvider} because {@code DeployWorkflowService} is gated on {@code
   * factory.deploy.trigger-enabled} and is therefore absent by default. A hard dependency would
   * make this controller — and with it the code-review webhook — fail to start whenever deploy
   * triggering is off, which is the opposite of the independence the two flags exist to give.
   */
  private final ObjectProvider<DeployWorkflowService> deployWorkflowService;

  public GitHubWebhookController(
      final CodeReviewProperties properties,
      final WebhookSignatureVerifier signatureVerifier,
      final ReviewWorkflowService workflowService,
      final ObjectMapper objectMapper,
      final FeedbackWorkflowService feedbackWorkflowService,
      final FeedbackProperties feedbackProperties,
      final DeployProperties deployProperties,
      final ObjectProvider<DeployWorkflowService> deployWorkflowService) {
    this.properties = properties;
    this.signatureVerifier = signatureVerifier;
    this.workflowService = workflowService;
    this.objectMapper = objectMapper;
    this.feedbackWorkflowService = feedbackWorkflowService;
    this.feedbackProperties = feedbackProperties;
    this.deployProperties = deployProperties;
    this.deployWorkflowService = deployWorkflowService;
  }

  @PostMapping
  public ResponseEntity<?> receive(
      @RequestHeader(value = "X-Hub-Signature-256", required = false)
          final String signature,
      @RequestHeader(value = "X-GitHub-Event", required = false) final String event,
      @RequestBody final byte[] body) {
    if (!signatureVerifier.isValid(body, signature, properties.github().webhookSecret())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new WebhookResponse("invalid"));
    }
    if ("workflow_run".equals(event)) {
      try {
        return handleWorkflowRun(readPayload(body));
      } catch (IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new WebhookResponse("malformed"));
      }
    }
    if (!"pull_request".equals(event)) {
      return ResponseEntity.accepted().body(new WebhookResponse("ignored"));
    }

    JsonNode payload;
    try {
      payload = readPayload(body);
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.badRequest().body(new WebhookResponse("malformed"));
    }
    String action = payload.path("action").asText();
    if ("closed".equals(action)) {
      return handleClosed(payload);
    }
    JsonNode pullRequest = payload.path("pull_request");
    if (!ACTIONS.contains(action)
        || (pullRequest.path("draft").asBoolean(false) && !"ready_for_review".equals(action))) {
      return ResponseEntity.accepted().body(new WebhookResponse("ignored"));
    }

    String owner = payload.path("repository").path("owner").path("login").asText();
    String repository = payload.path("repository").path("name").asText();
    int pullNumber = pullRequest.path("number").asInt();
    String headSha = pullRequest.path("head").path("sha").asText();
    long installationId = payload.path("installation").path("id").asLong();
    if (owner.isBlank() || repository.isBlank() || pullNumber < 1 || headSha.isBlank()) {
      return ResponseEntity.badRequest().body(new WebhookResponse("malformed"));
    }

    ReviewAccepted accepted =
        workflowService.start(
            new ReviewRequest(
                owner,
                repository,
                pullNumber,
                headSha,
                installationId > 0 ? installationId : null,
                true));
    return ResponseEntity.accepted().body(accepted);
  }

  /**
   * Turns a completed {@code Publish} build on {@code main} into a production deploy.
   *
   * <p><b>Why {@code workflow_run} and not {@code pull_request closed}.</b> Merge fires
   * {@code pull_request closed} immediately, while {@code Publish} then spends minutes building
   * three ARM images. Deploying on merge would pull the <em>previous</em> {@code :latest} and
   * report success — the worst available failure mode, because it looks like it worked.
   * {@code workflow_run} completion is the only event that means the images exist.
   *
   * <p>Anything that fails a condition gets the existing {@code 202 ignored} rather than an error:
   * GitHub retries non-2xx, and there is nothing here worth retrying. Note that {@code
   * workflow_run} also arrives with {@code action: requested} and {@code action: in_progress},
   * whose {@code conclusion} is null — the conclusion check alone filters those, so there is no
   * need to test {@code action} as well and no second condition to keep in step with GitHub.
   */
  private ResponseEntity<?> handleWorkflowRun(final JsonNode payload) {
    DeployWorkflowService service = deployWorkflowService.getIfAvailable();
    if (!deployProperties.triggerEnabled() || service == null) {
      return ResponseEntity.accepted().body(new WebhookResponse("ignored"));
    }

    JsonNode workflowRun = payload.path("workflow_run");
    String owner = payload.path("repository").path("owner").path("login").asText();
    String repository = payload.path("repository").path("name").asText();
    String headSha = workflowRun.path("head_sha").asText();

    if (!deployProperties.workflowName().equals(workflowRun.path("name").asText())
        || !"success".equals(workflowRun.path("conclusion").asText())
        || !deployProperties.branch().equals(workflowRun.path("head_branch").asText())
        || !deployProperties.slug().equals(owner + "/" + repository)
        || headSha.isBlank()) {
      return ResponseEntity.accepted().body(new WebhookResponse("ignored"));
    }

    long installationId = payload.path("installation").path("id").asLong();
    DeployAccepted accepted =
        service.start(
            headSha,
            DeployRequest.TRIGGER_WEBHOOK,
            installationId > 0 ? installationId : null);
    return ResponseEntity.accepted().body(accepted);
  }

  private ResponseEntity<?> handleClosed(final JsonNode payload) {
    JsonNode pullRequest = payload.path("pull_request");
    String owner = payload.path("repository").path("owner").path("login").asText();
    String repository = payload.path("repository").path("name").asText();
    int pullNumber = pullRequest.path("number").asInt();
    long installationId = payload.path("installation").path("id").asLong();
    if (owner.isBlank() || repository.isBlank() || pullNumber < 1) {
      return ResponseEntity.badRequest().body(new WebhookResponse("malformed"));
    }
    if (!feedbackProperties.enabled()
        || hasSkipLabel(pullRequest)
        || !repoAllowed(owner + "/" + repository)) {
      return ResponseEntity.accepted().body(new WebhookResponse("ignored"));
    }
    FeedbackAccepted accepted =
        feedbackWorkflowService.start(
            new FeedbackRequest(
                owner, repository, pullNumber,
                installationId > 0 ? installationId : null, false));
    return ResponseEntity.accepted().body(accepted);
  }

  private boolean hasSkipLabel(final JsonNode pullRequest) {
    for (JsonNode label : pullRequest.path("labels")) {
      if (feedbackProperties.skipLabel().equals(label.path("name").asText())) {
        return true;
      }
    }
    return false;
  }

  private boolean repoAllowed(final String slug) {
    return feedbackProperties.repos().isEmpty() || feedbackProperties.repos().contains(slug);
  }

  private JsonNode readPayload(final byte[] body) {
    try {
      return objectMapper.readTree(body);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Webhook body is not valid JSON", exception);
    }
  }

  private record WebhookResponse(String status) {
  }
}
