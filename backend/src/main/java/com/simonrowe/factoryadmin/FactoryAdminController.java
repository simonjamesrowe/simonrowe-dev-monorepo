package com.simonrowe.factoryadmin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-role-protected browser surface for safe Software Factory operations. */
@RestController
@RequestMapping("/api/admin/software-factory")
public class FactoryAdminController {

  private final FactoryAdminService service;

  public FactoryAdminController(final FactoryAdminService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public FactoryAdminStatus status() {
    return service.status();
  }

  @GetMapping("/runs/{workflowId}")
  public FactoryRunProgress progress(@PathVariable final String workflowId) {
    return service.progress(workflowId);
  }

  @PostMapping("/reviews")
  public ResponseEntity<FactoryRunAccepted> review(
      @Valid @RequestBody final ReviewRequest request) {
    return accepted(service.startCodeReview(request.pullNumber(), request.publish()));
  }

  @PostMapping("/feedback")
  public ResponseEntity<FactoryRunAccepted> feedback(
      @Valid @RequestBody final FeedbackRequest request) {
    return accepted(service.startFeedback(request.pullNumber()));
  }

  @PostMapping("/vulnerability-scans")
  public ResponseEntity<FactoryRunAccepted> vulnerabilityScan() {
    return accepted(service.startVulnerabilityScan());
  }

  @PostMapping("/platform-backups")
  public ResponseEntity<FactoryRunAccepted> platformBackup(
      @RequestBody final PlatformBackupRequest request) {
    return accepted(service.startPlatformBackup(request.dryRun()));
  }

  @PostMapping("/log-scans")
  public ResponseEntity<FactoryRunAccepted> logScan(
      @RequestBody final LogScanRequest request) {
    return accepted(service.startLogWatchScan(request.dryRun()));
  }

  @PostMapping("/deploys")
  public ResponseEntity<FactoryRunAccepted> deploy(
      @Valid @RequestBody final DeployRequest request) {
    return accepted(service.startDeploy(request.frontendCommit(), request.confirmation()));
  }

  private static ResponseEntity<FactoryRunAccepted> accepted(
      final FactoryRunAccepted response) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  public record ReviewRequest(@Min(1) int pullNumber, boolean publish) {
  }

  public record FeedbackRequest(@Min(1) int pullNumber) {
  }

  public record PlatformBackupRequest(boolean dryRun) {
  }

  public record LogScanRequest(boolean dryRun) {
  }

  public record DeployRequest(
      @NotBlank String frontendCommit, @NotBlank String confirmation) {
  }
}
