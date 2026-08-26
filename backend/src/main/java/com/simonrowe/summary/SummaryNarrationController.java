package com.simonrowe.summary;

import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.narration.NarrationService;
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
 * Spoken audio of an article's in-depth summary.
 *
 * <p>Same {@code NarrationResponse} contract as {@code /api/blogs/{blogId}/narration}, so
 * the frontend polling logic is shared rather than reimplemented.
 *
 * <p>Unlike the blog endpoint, the {@code POST} here requires a session. A text-to-speech
 * render is the most expensive operation in this feature and it draws on a monthly
 * character budget shared with blog narration; leaving it open would let an anonymous
 * caller drain it.
 */
@RestController
@RequestMapping("/api/news/{articleId}/summary/narration")
@Validated
public class SummaryNarrationController {

  private final NarrationService narrationService;

  public SummaryNarrationController(final NarrationService narrationService) {
    this.narrationService = narrationService;
  }

  /**
   * Current narration state, with optional long-poll.
   *
   * @param articleId the aggregated article id
   * @param afterVersion the version the client already has, or null for an immediate read
   * @param waitSeconds how long to hold the request open
   * @return the current state
   */
  @GetMapping
  public NarrationResponse getStatus(
      @PathVariable final String articleId,
      @RequestParam(required = false) final Long afterVersion,
      @RequestParam(defaultValue = "0") @Min(0) @Max(25) final int waitSeconds
  ) {
    return narrationService.getStatus(
        NarrationContentType.ARTICLE_SUMMARY, articleId, afterVersion, waitSeconds);
  }

  /**
   * Queues text-to-speech for the summary.
   *
   * @param articleId the aggregated article id
   * @return 202 when new work was queued, 200 when an existing narration was reused, or
   *     503 carrying an {@code UNAVAILABLE} response when text-to-speech is unconfigured
   */
  @PostMapping
  public ResponseEntity<NarrationResponse> request(
      @PathVariable final String articleId
  ) {
    NarrationService.RequestResult result = narrationService.request(
        NarrationContentType.ARTICLE_SUMMARY, articleId);
    if (result.response().state() == NarrationResponse.PublicState.UNAVAILABLE) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(result.response());
    }
    return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(result.response());
  }
}
