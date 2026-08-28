package com.simonrowe.factory.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal-network-only, read-only factory readiness endpoint.
 *
 * <p><strong>Deliberately unauthenticated</strong>, on the same terms as {@code /api/version}. The
 * backend asks both the {@code software-factory} and the {@code deployer} container for this, and
 * the deployer holds no {@code FACTORY_TRIGGER_TOKEN} on purpose — it receives no webhook and no
 * HTTP trigger, and giving it the token to satisfy a status probe would hand the container that
 * holds the Docker socket a credential that also authorises {@code /api/reviews}. Requiring a
 * token here would therefore make the deployer permanently report itself unreachable, which
 * disables the deploy and platform-backup actions with no way to recover from configuration.
 * What this returns is booleans, queue names, poller counts and schedule times: no credential, no
 * free text from a failing run, and nothing that is not already public in the repository.
 */
@RestController
@RequestMapping("/api/factory/status")
public class FactoryStatusController {

  private final FactoryStatusService service;

  public FactoryStatusController(final FactoryStatusService service) {
    this.service = service;
  }

  @GetMapping
  public FactoryStatusResponse status() {
    return service.status();
  }
}
