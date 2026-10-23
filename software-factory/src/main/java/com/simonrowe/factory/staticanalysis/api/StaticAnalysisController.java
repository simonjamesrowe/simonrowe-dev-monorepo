package com.simonrowe.factory.staticanalysis.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisProgress;
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

/** Token-protected manual static-analysis scan API; never routed by nginx. */
@RestController
@RequestMapping("/api/static-analysis-scans")
public class StaticAnalysisController {

  private final FactoryTokenAuthenticator authenticator;
  private final StaticAnalysisProperties properties;
  private final StaticAnalysisWorkflowService service;

  /**
   * Creates the controller.
   *
   * @param authenticator validates the factory trigger token
   * @param properties the module's configuration, read for its enabled flag
   * @param service starts and queries scans
   */
  public StaticAnalysisController(
      final FactoryTokenAuthenticator authenticator,
      final StaticAnalysisProperties properties,
      final StaticAnalysisWorkflowService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.service = service;
  }

  /**
   * Starts a scan.
   *
   * @param token the factory trigger token
   * @param request the dry-run flag; may be absent entirely
   * @return {@code 202} with the ids needed to follow the run
   */
  @PostMapping
  public ResponseEntity<StaticAnalysisScanAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody(required = false) final StaticAnalysisScanRequest request) {
    authenticator.authenticate(token);
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Static analysis is disabled");
    }
    boolean dryRun = request != null && request.isDryRun();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(dryRun));
  }

  /**
   * Reads a scan's progress.
   *
   * @param token the factory trigger token
   * @param workflowId the workflow to query
   * @return the current progress snapshot
   */
  @GetMapping("/{workflowId}")
  public StaticAnalysisProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    return service.progress(workflowId);
  }
}
