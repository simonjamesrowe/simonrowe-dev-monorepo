package com.simonrowe.reviewer.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.reviewer.api.ReviewAccepted;
import com.simonrowe.reviewer.api.ReviewWorkflowService;
import com.simonrowe.reviewer.config.ReviewerProperties;
import com.simonrowe.reviewer.domain.ReviewRequest;
import java.io.IOException;
import java.util.Set;
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

  private final ReviewerProperties properties;
  private final WebhookSignatureVerifier signatureVerifier;
  private final ReviewWorkflowService workflowService;
  private final ObjectMapper objectMapper;

  public GitHubWebhookController(
      final ReviewerProperties properties,
      final WebhookSignatureVerifier signatureVerifier,
      final ReviewWorkflowService workflowService,
      final ObjectMapper objectMapper) {
    this.properties = properties;
    this.signatureVerifier = signatureVerifier;
    this.workflowService = workflowService;
    this.objectMapper = objectMapper;
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
