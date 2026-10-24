package com.simonrowe.school.news;

import com.simonrowe.school.SchoolProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The newsletter and "From the school" cards on the Term Time page.
 *
 * <p>Public, and fixed to public content by {@link SchoolNewsService}. Not rate limited, like
 * {@code /api/school/calendar}: the page reads both on load, and each is a few indexed Mongo
 * reads with no model call behind it. Summaries are written on a schedule, never here.
 */
@RestController
@RequestMapping("/api/school")
public class SchoolNewsController {

  static final int DEFAULT_NEWSLETTERS = 6;
  static final int MAX_NEWSLETTERS = 12;
  static final int DEFAULT_MESSAGES = 5;
  static final int MAX_MESSAGES = 10;

  private final SchoolNewsService news;
  private final SchoolProperties properties;

  public SchoolNewsController(final SchoolNewsService news, final SchoolProperties properties) {
    this.news = news;
    this.properties = properties;
  }

  /**
   * The newest newsletters, each with its summary once written.
   *
   * @param limit how many, capped at {@value #MAX_NEWSLETTERS}
   * @return newsletters newest first, or 503 when Term Time is switched off
   */
  @GetMapping("/newsletters")
  public ResponseEntity<List<NewsletterResponse>> newsletters(
      @RequestParam(name = "limit", required = false) final Integer limit) {
    if (!properties.enabled()) {
      return ResponseEntity.status(503).build();
    }
    return ResponseEntity.ok(news.newsletters(clamp(limit, DEFAULT_NEWSLETTERS, MAX_NEWSLETTERS))
        .stream().map(NewsletterResponse::from).toList());
  }

  /**
   * Recent emails from the school, approved for the public.
   *
   * @param limit how many, capped at {@value #MAX_MESSAGES}
   * @return messages newest first, or 503 when Term Time is switched off
   */
  @GetMapping("/messages")
  public ResponseEntity<List<SchoolNewsService.Message>> messages(
      @RequestParam(name = "limit", required = false) final Integer limit) {
    if (!properties.enabled()) {
      return ResponseEntity.status(503).build();
    }
    return ResponseEntity.ok(news.recentMessages(clamp(limit, DEFAULT_MESSAGES, MAX_MESSAGES)));
  }

  private static int clamp(final Integer requested, final int fallback, final int max) {
    if (requested == null || requested < 1) {
      return fallback;
    }
    return Math.min(requested, max);
  }

  /**
   * One newsletter as the page shows it.
   *
   * <p>The summary's bookkeeping (hash, model, when it was written) is left out.
   *
   * @param id the document id
   * @param title the newsletter's title as the school gave it
   * @param url where the school published it
   * @param publishedAt the date it carries
   * @param headline the summary's headline, or null while no summary has been written
   * @param points the summary's points, empty while no summary has been written
   */
  public record NewsletterResponse(
      String id, String title, String url, Instant publishedAt, String headline,
      List<NewsletterSummary.Point> points) {

    static NewsletterResponse from(final SchoolNewsService.Newsletter newsletter) {
      final NewsletterSummary summary = newsletter.summary();
      return new NewsletterResponse(newsletter.id(), newsletter.title(), newsletter.url(),
          newsletter.publishedAt(), summary == null ? null : summary.headline(),
          summary == null ? List.of() : summary.points());
    }
  }
}
