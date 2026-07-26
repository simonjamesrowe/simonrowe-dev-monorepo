package com.simonrowe.reviewer.api;

import com.simonrowe.reviewer.config.ReviewerProperties;
import com.simonrowe.reviewer.domain.ReviewProgress;
import com.simonrowe.reviewer.domain.ReviewRequest;
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

/** Small internal trigger and status API; GitHub webhooks use a separate signed endpoint. */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

  private final ReviewerProperties properties;
  private final ReviewWorkflowService workflowService;

  public ReviewController(
      final ReviewerProperties properties, final ReviewWorkflowService workflowService) {
    this.properties = properties;
    this.workflowService = workflowService;
  }

  @PostMapping
  public ResponseEntity<ReviewAccepted> start(
      @RequestHeader(value = "X-Reviewer-Token", required = false) final String token,
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
  public ReviewProgress progress(@PathVariable final String workflowId) {
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
