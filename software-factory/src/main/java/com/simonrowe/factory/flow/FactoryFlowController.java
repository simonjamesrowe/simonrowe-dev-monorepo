package com.simonrowe.factory.flow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The factory flow graph, for the admin console.
 *
 * <p><strong>Deliberately unauthenticated</strong>, on the same terms as {@code
 * /api/factory/status}. This response is node keys and labels from a fixed topology, integer
 * counts, and {@code diagnostic} strings of exactly the same kind {@code /api/factory/status}
 * already serves openly from both containers — no credential, no ticket title, no pull request
 * subject. The {@code deploy} and {@code platformbackup} nodes are deployer-owned, and the
 * deployer deliberately holds no {@code FACTORY_TRIGGER_TOKEN} (it receives no webhook and no HTTP
 * trigger); token-protecting this endpoint would therefore have forced either a role-conditional
 * authentication bypass or handing the socket-holding container a credential that also authorises
 * {@code /api/reviews} and {@code /api/deploys} — the same trap {@code FactoryStatusController}
 * documents.
 *
 * <p>Titles and identifiers — ticket subjects, pull request titles — appear only at the
 * per-node detail endpoint, {@code GET /api/factory/flow/{nodeKey}}, which
 * <strong>is</strong> token-protected. Do not "tidy" this class back to protected: that would
 * either break the deployer's half of the graph or require the exemption this endpoint exists
 * specifically to avoid.
 *
 * <p>That detail endpoint is deliberately {@link FactoryFlowDetailController}, a separate class,
 * even though both share the {@code /api/factory/flow} base path. {@code
 * FactoryTokenAuthenticator} is a plain component each protected controller calls for itself, not
 * a Spring Security filter — so a {@code @GetMapping("/{nodeKey}")} added here would silently
 * inherit this class's unauthenticated posture instead of gaining the check.
 */
@RestController
@RequestMapping("/api/factory/flow")
public class FactoryFlowController {

  private final FactoryFlowService service;

  public FactoryFlowController(final FactoryFlowService service) {
    this.service = service;
  }

  /**
   * Returns the whole graph.
   *
   * @return every node with its live figures, and every edge
   */
  @GetMapping
  public FactoryFlowResponse flow() {
    return service.flow();
  }
}
