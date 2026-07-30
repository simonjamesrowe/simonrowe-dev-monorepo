package com.simonrowe.factory.codereview.api;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ReviewProgress;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
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
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

  private final CodeReviewProperties properties;
  private final ReviewWorkflowService workflowService;

  public ReviewController(
      final CodeReviewProperties properties, final ReviewWorkflowService workflowService) {
    this.properties = properties;
    this.workflowService = workflowService;
  }

  @PostMapping
  public ResponseEntity<ReviewAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @Valid @RequestBody final ManualReviewRequest request) {
    authenticate(token);
    ReviewAccepted accepted =
        workflowService.start(
            new ReviewRequest(
                request.owner(),
                request.repository(),
                request.pullNumber(),
                request.expectedHeadSha(),
                null,
                request.publish()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
  }

  @GetMapping("/{workflowId}")
  public ReviewProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticate(token);
    return workflowService.progress(workflowId);
  }

  private void authenticate(final String suppliedToken) {
    String configured = properties.api().triggerToken();
    if (configured == null || configured.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Manual review trigger is disabled");
    }
    byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
    byte[] supplied =
        suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, supplied)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
