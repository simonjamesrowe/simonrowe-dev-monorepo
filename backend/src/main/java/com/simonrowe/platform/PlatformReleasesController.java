package com.simonrowe.platform;

import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The changelog: every stored release with its AI-written release note, a page at a time.
 *
 * <p>Public, no authentication, and serves only stored data — see
 * {@link PlatformStatusController} for why neither is metered.
 *
 * <p>The entry flagged {@code running} is the commit the backend was built from. Since Publish
 * stopped rebuilding images whose code did not change, that is often older than the newest
 * commit deployed; the others evidence that a commit reached {@code main} and was published.
 */
@RestController
@RequestMapping("/api/platform")
@Validated
public class PlatformReleasesController {

  private final ReleaseQueryService queryService;

  /**
   * Creates the controller.
   *
   * @param queryService pages and searches the stored releases
   */
  public PlatformReleasesController(final ReleaseQueryService queryService) {
    this.queryService = queryService;
  }

  /**
   * One page of releases, newest first.
   *
   * @param page the zero-based page number
   * @param size the page size; clamped server-side to {@value ReleaseQueryService#MAX_PAGE_SIZE}
   * @param type a conventional-commit type to keep, such as {@code feat}; absent for all
   * @param q free text matched against the subject, the release note and the SHA
   * @return the page; empty items when nothing has been recorded or nothing matches, never a 404
   */
  @GetMapping("/releases")
  public ReleasePage releases(
      @RequestParam(defaultValue = "0") @Min(0) final int page,
      @RequestParam(defaultValue = "" + ReleaseQueryService.DEFAULT_PAGE_SIZE) @Min(1)
      final int size,
      @RequestParam(required = false) final String type,
      @RequestParam(required = false) final String q) {
    return queryService.find(page, size, type, q);
  }
}
