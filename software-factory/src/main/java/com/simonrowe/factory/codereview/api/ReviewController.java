package com.simonrowe.factory.codereview.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.domain.ReviewProgress;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
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
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

  private final FactoryTokenAuthenticator authenticator;
  private final ReviewWorkflowService workflowService;
  private final GitHubCredentials credentials;

  public ReviewController(
      final FactoryTokenAuthenticator authenticator,
      final ReviewWorkflowService workflowService,
      final GitHubCredentials credentials) {
    this.authenticator = authenticator;
    this.workflowService = workflowService;
    this.credentials = credentials;
  }

  @PostMapping
  public ResponseEntity<ReviewAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @Valid @RequestBody final ManualReviewRequest request) {
    authenticator.authenticate(token);
    ReviewAccepted accepted =
        workflowService.start(
            new ReviewRequest(
                request.owner(),
                request.repository(),
                request.pullNumber(),
                request.expectedHeadSha(),
                credentials.installationId(request.owner(), request.repository()),
                request.publish()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  @GetMapping("/{workflowId}")
  public ReviewProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    return workflowService.progress(workflowId);
  }

}
