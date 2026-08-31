package com.simonrowe.factory.logwatch.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
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

/** Token-protected manual log-scan API; never routed by nginx. */
@RestController
@RequestMapping("/api/logwatch/scans")
public class LogWatchController {

  private final FactoryTokenAuthenticator authenticator;
  private final LogWatchProperties properties;
  private final LogWatchWorkflowService service;

  /**
   * Creates the controller.
   *
   * @param authenticator validates the factory trigger token
   * @param properties the module's configuration, read for its enabled flag
   * @param service starts and queries scans
   */
  public LogWatchController(
      final FactoryTokenAuthenticator authenticator,
      final LogWatchProperties properties,
      final LogWatchWorkflowService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.service = service;
  }

  /**
   * Starts a scan.
   *
   * @param token the factory trigger token
   * @param request the window and dry-run flag; may be absent entirely
   * @return {@code 202} with the ids needed to follow the run
   */
  @PostMapping
  public ResponseEntity<LogWatchScanAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody(required = false) final LogWatchScanRequest request) {
    authenticator.authenticate(token);
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Log watch is disabled");
    }
    LogWatchScanRequest effective =
        request == null ? new LogWatchScanRequest(null, null, false) : request;
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            service.start(
                effective.windowStart(), effective.windowEnd(), effective.isDryRun()));
  }

  /**
   * Reads a scan's progress.
   *
   * @param token the factory trigger token
   * @param workflowId the workflow to query
   * @return the current progress snapshot
   */
  @GetMapping("/{workflowId}")
  public LogWatchProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    return service.progress(workflowId);
  }
}
