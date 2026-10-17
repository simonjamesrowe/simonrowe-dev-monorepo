package com.simonrowe.factory.writing.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.writing.config.WritingProperties;
import java.util.regex.Pattern;
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
 * Token-protected content-writing API; never routed by nginx.
 *
 * <p>Its own controller, like every trigger endpoint: {@link FactoryTokenAuthenticator} is a
 * plain component each controller calls for itself, not a Spring Security filter, so a mapping
 * added to an unauthenticated controller would silently inherit that controller's posture.
 *
 * <p>Only the backend's own scheduled jobs call this. It must never be reachable from anything a
 * site visitor can trigger: the work runs on Simon's Claude subscription, whose terms allow
 * ordinary individual use only (see {@code ContentWritingActivitiesImpl}).
 */
@RestController
@RequestMapping("/api/writing/jobs")
public class ContentWritingController {

  /**
   * A job id is the tail of a Temporal workflow id and appears in logs, so it is held to a plain
   * charset: a letter or digit first, then letters, digits, dot, underscore or hyphen.
   */
  private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  private final FactoryTokenAuthenticator authenticator;
  private final WritingProperties properties;
  private final ContentWritingService service;

  public ContentWritingController(
      final FactoryTokenAuthenticator authenticator,
      final WritingProperties properties,
      final ContentWritingService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.service = service;
  }

  /**
   * Submits a job idempotently.
   *
   * @param token the factory trigger token
   * @param request the job id and prompt
   * @return {@code 202} when this call started the job, {@code 200} with its current state when it
   *     was already running or had completed
   */
  @PostMapping
  public ResponseEntity<WritingJobStatus> submit(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody(required = false) final WritingJobRequest request) {
    authenticator.authenticate(token);
    requireEnabled();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A job needs a body");
    }
    String jobId = validJobId(request.jobId());
    String prompt = request.prompt();
    if (prompt == null || prompt.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A job needs a prompt");
    }
    if (prompt.length() > properties.maxPromptChars()) {
      throw new ResponseStatusException(
          HttpStatus.CONTENT_TOO_LARGE,
          "A prompt may be at most %d characters".formatted(properties.maxPromptChars()));
    }
    ContentWritingService.Submission submission = service.submit(jobId, prompt);
    return ResponseEntity.status(submission.started() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(submission.status());
  }

  /**
   * Reads a job.
   *
   * @param token the factory trigger token
   * @param jobId the job id it was submitted under
   * @return the job's state, with its text once completed
   */
  @GetMapping("/{jobId}")
  public WritingJobStatus status(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String jobId) {
    authenticator.authenticate(token);
    requireEnabled();
    return service.status(validJobId(jobId));
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Content writing is disabled");
    }
  }

  private static String validJobId(final String jobId) {
    if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "A job id is 1-128 letters, digits, '.', '_' or '-', starting with a letter or digit");
    }
    return jobId;
  }
}
