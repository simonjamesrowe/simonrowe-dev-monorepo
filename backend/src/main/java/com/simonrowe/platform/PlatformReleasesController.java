package com.simonrowe.platform;

import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The changelog: recent releases with their AI-written release notes.
 *
 * <p>Public, no authentication, and serves only stored data — see
 * {@link PlatformStatusController} for why neither is metered.
 *
 * <p>Entries other than the one flagged {@code running} evidence that an image was
 * <em>published</em>, not that it was deployed: {@code deploy_runs} is empty because
 * auto-deploy is off, so deployment history does not exist to report. The page's wording
 * carries that distinction.
 */
@RestController
@RequestMapping("/api/platform")
@Validated
public class PlatformReleasesController {

  private static final int DEFAULT_LIMIT = 20;

  private final PlatformStatusService statusService;

  /**
   * Creates the controller.
   *
   * @param statusService the service that assembles the changelog
   */
  public PlatformReleasesController(final PlatformStatusService statusService) {
    this.statusService = statusService;
  }

  /**
   * Recent releases, newest first.
   *
   * @param limit how many to return; clamped server-side to 100
   * @return the entries; an empty array when nothing has been seeded, never a 404
   */
  @GetMapping("/releases")
  public List<ReleaseResponse> releases(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) final int limit) {
    return statusService.releases(limit);
  }
}
