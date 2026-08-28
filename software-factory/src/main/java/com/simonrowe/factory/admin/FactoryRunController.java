package com.simonrowe.factory.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token-protected progress for any factory run; nginx never routes this API.
 *
 * <p>Unlike {@link FactoryStatusController} this does require the shared token: a workflow's
 * {@code detail} carries free-text diagnostics from a failing run, which is a different
 * disclosure class from the boolean readiness the status endpoint returns.
 */
@RestController
@RequestMapping("/api/factory/runs")
public class FactoryRunController {

  /** Long enough for every workflow id the factory mints, all of which are id-plus-UUID. */
  private static final int MAX_WORKFLOW_ID = 128;

  private final FactoryTokenAuthenticator authenticator;
  private final FactoryRunStatusService service;

  public FactoryRunController(
      final FactoryTokenAuthenticator authenticator, final FactoryRunStatusService service) {
    this.authenticator = authenticator;
    this.service = service;
  }

  @GetMapping("/{workflowId}")
  public FactoryRunProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    if (workflowId.length() > MAX_WORKFLOW_ID || !workflowId.matches("[A-Za-z0-9._:-]+")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed workflow id");
    }
    return service.progress(workflowId);
  }
}
