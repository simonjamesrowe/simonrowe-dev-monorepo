package com.simonrowe.summary;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand in-depth summaries of aggregated news articles. Reads are public because the
 * artefact is globally shared; the {@code POST} requires a session (any valid JWT — not
 * admin-role gated, see {@code SecurityConfig}) because it spends on the model.
 */
@RestController
@RequestMapping("/api/news/{articleId}/summary")
@Validated
public class ArticleSummaryController {

  private final ArticleSummaryService summaryService;

  public ArticleSummaryController(final ArticleSummaryService summaryService) {
    this.summaryService = summaryService;
  }

  /**
   * Current state, with optional long-poll.
   *
   * <p>An article that exists but has no summary yields {@code 200 NOT_REQUESTED}, not a
   * 404 — the article is there, the summary is not, and the client needs to tell those
   * apart.
   *
   * @param articleId the aggregated article id
   * @param afterVersion return as soon as the version differs from this; omit for an
   *     immediate read
   * @param waitSeconds how long to hold the request open, bounded to keep a long-poll from
   *     pinning a thread indefinitely
   * @return the current state
   */
  @GetMapping
  public ArticleSummaryResponse getStatus(
      @PathVariable final String articleId,
      @RequestParam(required = false) final Long afterVersion,
      @RequestParam(defaultValue = "0") @Min(0) @Max(25) final int waitSeconds
  ) {
    return summaryService.getStatus(articleId, afterVersion, waitSeconds);
  }

  /**
   * Requests generation.
   *
   * <p>Blocks for the duration of the model call — roughly 15-30 seconds — and returns the
   * finished summary. A {@code 202} means another caller already owns the generation and
   * this client should poll {@link #getStatus} instead. A stored failure comes back as a
   * {@code 200} carrying {@code FAILED}: the request itself succeeded, and the client needs
   * the {@code retryable} flag to decide whether offering a retry is honest.
   *
   * @param articleId the aggregated article id
   * @return the summary, or the in-progress state
   */
  @PostMapping
  public ResponseEntity<ArticleSummaryResponse> request(
      @PathVariable final String articleId
  ) {
    ArticleSummaryService.RequestResult result = summaryService.request(articleId);
    return ResponseEntity
        .status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(result.response());
  }
}
