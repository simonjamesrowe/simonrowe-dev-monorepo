package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token-protected detail for one factory flow node; nginx never routes this API.
 *
 * <p>Deliberately a separate controller from {@link FactoryFlowController}, even though both are
 * mapped under {@code /api/factory/flow}. {@code FactoryTokenAuthenticator} is not a Spring
 * Security filter — it is a plain component each protected controller calls for itself as the
 * first line of its handler — so a {@code @GetMapping("/{nodeKey}")} added to {@link
 * FactoryFlowController} would silently inherit that class's deliberately unauthenticated
 * posture. This response carries pull request titles and Linear ticket subjects (via {@link
 * FlowDetail.Item#title()}), which is exactly the disclosure class {@link FactoryFlowController}'s
 * Javadoc says must stay behind the token, unlike the counts and diagnostics {@code GET
 * /api/factory/flow} already serves openly.
 */
@RestController
@RequestMapping("/api/factory/flow")
public class FactoryFlowDetailController {

  private final FactoryTokenAuthenticator authenticator;
  private final FactoryFlowDetailService service;

  public FactoryFlowDetailController(
      final FactoryTokenAuthenticator authenticator, final FactoryFlowDetailService service) {
    this.authenticator = authenticator;
    this.service = service;
  }

  /**
   * Returns the work behind one node.
   *
   * @param token the shared factory trigger token
   * @param nodeKey the node whose drawer is open
   * @return that node's items, newest first
   */
  @GetMapping("/{nodeKey}")
  public FlowDetail detail(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String nodeKey) {
    authenticator.authenticate(token);
    return service.detail(nodeKey);
  }
}
