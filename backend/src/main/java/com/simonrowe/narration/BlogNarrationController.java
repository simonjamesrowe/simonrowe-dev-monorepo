package com.simonrowe.narration;

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
 * Blog narration. Same path and same response body as before narration was generalised.
 *
 * <p><strong>The {@code POST} is authenticated.</strong> It was public for most of this
 * endpoint's life — its contract predated the text-to-speech budget concern, and that
 * asymmetry with the summary narration {@code POST} next door was recorded here as
 * deliberate. It stopped being defensible when the blog and news listings gained a Listen
 * control on every card: a render draws on the same 1,000,000 chars/month budget as summary
 * narration, so gating only the listing would have left the identical post anonymously
 * narratable from its detail page. Both writes are now gated alike — see
 * {@code SecurityConfig}. Any valid JWT suffices; there is no admin-role requirement.
 *
 * <p>{@code GET} stays public. The audio is globally shared content rather than per-reader
 * state, and a signed-out reader has to be able to play what already exists.
 */
@RestController
@RequestMapping("/api/blogs/{blogId}/narration")
@Validated
public class BlogNarrationController {

  private final NarrationService narrationService;

  public BlogNarrationController(final NarrationService narrationService) {
    this.narrationService = narrationService;
  }

  @GetMapping
  public NarrationResponse getStatus(
      @PathVariable final String blogId,
      @RequestParam(required = false) final Long afterVersion,
      @RequestParam(defaultValue = "0") @Min(0) @Max(25) final int waitSeconds
  ) {
    return narrationService.getStatus(
        NarrationContentType.BLOG, blogId, afterVersion, waitSeconds);
  }

  @PostMapping
  public ResponseEntity<NarrationResponse> request(
      @PathVariable final String blogId
  ) {
    NarrationService.RequestResult result = narrationService.request(
        NarrationContentType.BLOG, blogId);
    if (result.response().state() == NarrationResponse.PublicState.UNAVAILABLE) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(result.response());
    }
    return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(result.response());
  }
}
