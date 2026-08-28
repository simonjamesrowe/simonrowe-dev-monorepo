package com.simonrowe.factory.deploy.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Token-protected manual redeploy endpoint; nginx never routes this API. */
@RestController
@RequestMapping("/api/deploys")
public class DeployController {

  private final FactoryTokenAuthenticator authenticator;
  private final DeployWorkflowService workflows;

  public DeployController(
      final FactoryTokenAuthenticator authenticator, final DeployWorkflowService workflows) {
    this.authenticator = authenticator;
    this.workflows = workflows;
  }

  @PostMapping
  public ResponseEntity<DeployAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @Valid @RequestBody final ManualDeployRequest request) {
    authenticator.authenticate(token);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(workflows.start(request.sha().toLowerCase(), DeployRequest.TRIGGER_MANUAL, null));
  }

  @GetMapping("/current")
  public DeployProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token) {
    authenticator.authenticate(token);
    return workflows.progress();
  }
}
