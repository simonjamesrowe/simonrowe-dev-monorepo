package com.simonrowe.narration;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk "what can I play right now" for a listing page.
 *
 * <p>Public, no authentication. Generated audio is globally shared — one MP3 serves everybody —
 * so which items have it is not per-reader information. This mirrors
 * {@code GET /api/news/summaries/ids} and {@code GET /api/favourites/{type}/ids}, the established
 * pattern for handing a listing page cheap bulk state in one call. Making it authenticated would
 * also defeat its purpose: a signed-out reader must see the duration on the card and be able to
 * press play.
 *
 * <p><strong>This endpoint is a necessity, not an optimisation.</strong>
 * {@code RateLimitInterceptor} meters {@code /api/blogs/{id}/narration} at 10 requests/minute per
 * IP for <em>every</em> method — its POST-only exemption lives in the summary branch only, not the
 * blog one. So a listing page whose cards each polled their own narration status would exhaust
 * the bucket on first render and then 429 the reader's actual click. One bulk read is the only
 * workable path.
 *
 * <p>The path is deliberately outside that pattern: {@code /api/narrations/ready} neither starts
 * with {@code /api/blogs/} nor ends with {@code /narration}, so {@code isNarrationPath} does not
 * match it and a page load spends nothing from the narration bucket. Do not move this under
 * {@code /api/blogs/…} — that would silently reintroduce the 429.
 */
@RestController
@RequestMapping("/api/narrations")
public class NarrationReadyController {

  private final NarrationService narrationService;

  public NarrationReadyController(final NarrationService narrationService) {
    this.narrationService = narrationService;
  }

  /**
   * Every item of the given content type that currently has playable audio.
   *
   * <p>At most one entry per content id — the newest {@code READY} narration. An empty list when
   * nothing has been narrated yet, never a 404: "no audio anywhere" is a normal state for a
   * listing page and every card simply reads "Listen".
   *
   * @param contentType which kind of content to list; an unrecognised value is a 400
   * @return the ready narrations
   */
  @GetMapping("/ready")
  public List<ReadyNarration> listReady(
      @RequestParam final NarrationContentType contentType
  ) {
    return narrationService.readyNarrations(contentType);
  }
}
