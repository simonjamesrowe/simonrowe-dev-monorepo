package com.simonrowe.factory.feedback.api;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Small internal trigger and status API; GitHub webhooks use a separate signed endpoint.
 *
 * <p>Every method here requires {@code X-Factory-Token}. nginx does not route either path, so
 * these are unreachable from the internet, but the token check is what actually holds: this
 * process also terminates the public webhook, and a proxy rule is not an authorisation boundary.
 * The trigger token is shared with {@code /api/reviews} on purpose — both are internal-only
 * triggers guarded by the same {@link CodeReviewProperties.Api}.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

  private final CodeReviewProperties properties;
  private final FeedbackWorkflowService workflowService;
  private final GitHubCredentials credentials;

  public FeedbackController(
      final CodeReviewProperties properties,
      final FeedbackWorkflowService workflowService,
      final GitHubCredentials credentials) {
    this.properties = properties;
    this.workflowService = workflowService;
    this.credentials = credentials;
  }

  @PostMapping
  public ResponseEntity<FeedbackAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @Valid @RequestBody final ManualFeedbackRequest request) {
    authenticate(token);
    FeedbackAccepted accepted =
        workflowService.start(
            new FeedbackRequest(
                request.owner(),
                request.repository(),
                request.pullNumber(),
                credentials.installationId(request.owner(), request.repository()),
                request.dryRun()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  @GetMapping("/{workflowId}")
  public FeedbackProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticate(token);
    return workflowService.progress(workflowId);
  }

  private void authenticate(final String suppliedToken) {
    String configured = properties.api().triggerToken();
    if (configured == null || configured.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Manual feedback trigger is disabled");
    }
    byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
    byte[] supplied =
        suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
