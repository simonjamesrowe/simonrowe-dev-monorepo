package com.simonrowe.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin dashboard's panels, one endpoint each so a slow or failing source never holds up
 * the others.
 *
 * <p>Under {@code /api/admin/**}, which {@code SecurityConfig} gates on the admin role.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

  static final int MAX_DAYS = 120;
  static final int MAX_WEEKS = 52;

  private final UptimeService uptimeService;
  private final DependencyTrackReader dependencyTrackReader;
  private final ContentFlowService contentFlowService;

  /**
   * Creates the controller.
   *
   * @param uptimeService the uptime panel
   * @param dependencyTrackReader the vulnerabilities panel
   * @param contentFlowService the reading panel
   */
  public AdminDashboardController(
      final UptimeService uptimeService,
      final DependencyTrackReader dependencyTrackReader,
      final ContentFlowService contentFlowService) {
    this.uptimeService = uptimeService;
    this.dependencyTrackReader = dependencyTrackReader;
    this.contentFlowService = contentFlowService;
  }

  /**
   * Uptime per public hostname, one cell per day.
   *
   * @param days how many days back, clamped to 1..120 (the probe results live 120 days)
   * @return the report
   */
  @GetMapping("/uptime")
  public UptimeReport uptime(@RequestParam(defaultValue = "90") final int days) {
    return uptimeService.report(Math.clamp(days, 1, MAX_DAYS));
  }

  /**
   * Findings per Dependency-Track project and the portfolio trend.
   *
   * @return the report; a Dependency-Track failure is in its status, not an error response
   */
  @GetMapping("/vulnerabilities")
  public VulnerabilityReport vulnerabilities() {
    return dependencyTrackReader.read();
  }

  /**
   * Articles arrived and summarised per week.
   *
   * @param weeks how many weeks back, clamped to 1..52
   * @return the report
   */
  @GetMapping("/content-flow")
  public ContentFlowReport contentFlow(@RequestParam(defaultValue = "12") final int weeks) {
    return contentFlowService.weekly(Math.clamp(weeks, 1, MAX_WEEKS));
  }
}
