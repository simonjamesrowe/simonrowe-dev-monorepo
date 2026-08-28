package com.simonrowe.factory.cvefix.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Token-protected manual vulnerability scan API; never routed by nginx. */
@RestController
@RequestMapping("/api/vulnerability-scans")
public class CveScanController {

  private final FactoryTokenAuthenticator authenticator;
  private final CveFixProperties properties;
  private final CveScanWorkflowService service;

  public CveScanController(
      final FactoryTokenAuthenticator authenticator,
      final CveFixProperties properties,
      final CveScanWorkflowService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<CveScanAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token) {
    authenticator.authenticate(token);
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Vulnerability scanning is disabled");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start());
  }

  @GetMapping("/{workflowId}")
  public CveFixProgress progress(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String workflowId) {
    authenticator.authenticate(token);
    return service.progress(workflowId);
  }
}
