package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The factory flow graph, for the admin console.
 *
 * <p><strong>Token-protected, unlike {@code /api/factory/status}.</strong> That endpoint is
 * deliberately open because it returns only booleans, queue names and poller counts, and because
 * the deployer holds no trigger token and must still be able to answer it. This one returns
 * Linear ticket counts and pull request figures, which is free text about work in progress, so it
 * belongs with {@code /api/factory/runs/&#123;id&#125;} behind {@link FactoryTokenAuthenticator}.
 *
 * <p>{@code FactoryTokenAuthenticator} has no path list or prefix allowlist of its own — there is
 * no security filter chain anywhere in this module. Every protected endpoint enrols itself by
 * taking an {@code X-Factory-Token} header and calling {@code authenticate} directly, the same as
 * {@code FactoryRunController} and {@code DeployController}; this controller does the same rather
 * than relying on the request mapping alone.
 */
@RestController
@RequestMapping("/api/factory/flow")
public class FactoryFlowController {

  private final FactoryTokenAuthenticator authenticator;
  private final FactoryFlowService service;

  public FactoryFlowController(
      final FactoryTokenAuthenticator authenticator, final FactoryFlowService service) {
    this.authenticator = authenticator;
    this.service = service;
  }

  /**
   * Returns the whole graph.
   *
   * @param token the shared factory trigger token
   * @return every node with its live figures, and every edge
   */
  @GetMapping
  public FactoryFlowResponse flow(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token) {
    authenticator.authenticate(token);
    return service.flow();
  }
}
