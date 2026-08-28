package com.simonrowe.factory.platformbackup.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupProgress;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Token-protected manual platform backup API; nginx never routes it. */
@RestController
@RequestMapping("/api/platform-backups")
public class PlatformBackupController {

  private final FactoryTokenAuthenticator authenticator;
  private final PlatformBackupWorkflowService workflows;

  public PlatformBackupController(
      final FactoryTokenAuthenticator authenticator,
      final PlatformBackupWorkflowService workflows) {
    this.authenticator = authenticator;
    this.workflows = workflows;
  }

  @PostMapping
  public ResponseEntity<PlatformBackupAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody final ManualPlatformBackupRequest request) {
    authenticator.authenticate(token);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(workflows.start(request.dryRun()));
  }

  @GetMapping("/current")
  public PlatformBackupProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token) {
    authenticator.authenticate(token);
    return workflows.progress();
  }
}
