package com.simonrowe.platform;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is running in production right now.
 *
 * <p>Public, no authentication — {@code SecurityConfig} ends with
 * {@code .anyRequest().permitAll()}, so this needs no matcher; {@code SecurityConfigTest}
 * asserts that posture deliberately rather than by accident. Everything here is already
 * public information: image tags come from a public compose file and commit SHAs from a public
 * repository.
 *
 * <p><b>Deliberately not metered by {@code RateLimitInterceptor}.</b> The page issues this
 * request plus one for the changelog on every view, and the footer badge on every page reads
 * from the bundle rather than from here precisely so that a site-wide fetch is avoided.
 * Adding this path to the interceptor would break the page for ordinary readers.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformStatusController {

  private final PlatformStatusService statusService;

  /**
   * Creates the controller.
   *
   * @param statusService the service that assembles the status payload
   */
  public PlatformStatusController(final PlatformStatusService statusService) {
    this.statusService = statusService;
  }

  /**
   * The running services and the platform components.
   *
   * @return the status; never a 404, an empty component list is a valid answer
   */
  @GetMapping("/status")
  public PlatformStatusResponse status() {
    return statusService.status();
  }
}
