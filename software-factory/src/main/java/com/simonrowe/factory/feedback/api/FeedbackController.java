package com.simonrowe.factory.feedback.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.linear.config.LinearProperties;
import jakarta.validation.Valid;
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

  private final FactoryTokenAuthenticator authenticator;
  private final FeedbackProperties feedbackProperties;
  private final FeedbackWorkflowService workflowService;
  private final GitHubCredentials credentials;
  private final LinearProperties linearProperties;

  public FeedbackController(
      final FactoryTokenAuthenticator authenticator,
      final FeedbackProperties feedbackProperties,
      final FeedbackWorkflowService workflowService,
      final GitHubCredentials credentials,
      final LinearProperties linearProperties) {
    this.authenticator = authenticator;
    this.feedbackProperties = feedbackProperties;
    this.workflowService = workflowService;
    this.credentials = credentials;
    this.linearProperties = linearProperties;
  }

  @PostMapping
  public ResponseEntity<FeedbackAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @Valid @RequestBody final ManualFeedbackRequest request) {
    authenticator.authenticate(token);
    if (!feedbackProperties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Feedback processing is disabled");
    }
    FeedbackAccepted accepted =
        workflowService.start(
            new FeedbackRequest(
                request.owner(),
                request.repository(),
                request.pullNumber(),
                credentials.installationId(request.owner(), request.repository()),
                request.dryRun(),
                linearProperties.enabled()));
    // started=false means the workflow id is not currently eligible to (re)start — either a
    // prior run is still in flight, or it already completed successfully and
    // ALLOW_DUPLICATE_FAILED_ONLY refuses to replace that. A 202 here would silently claim
    // acceptance for a re-drive that did nothing; 409 makes that visible to the caller.
    HttpStatus status = accepted.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
    return ResponseEntity.status(status).body(accepted);
  }

  @GetMapping("/{workflowId}")
  public FeedbackProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    return workflowService.progress(workflowId);
  }

}
