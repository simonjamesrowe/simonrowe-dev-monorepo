package com.simonrowe.shortlink;

import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.common.LogSafe;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Serves {@code GET /s/{slug}} — the share link.
 *
 * <p>Deliberately outside {@code /api/}, so it is routed explicitly: {@code frontend/
 * nginx.conf} in production (bind-mounted from the deploy directory, so it does not ship
 * with the frontend image) and {@code vite.config.ts} in local development.
 *
 * <p>Three decisions worth not undoing:
 *
 * <ul>
 *   <li><b>Every client gets the same {@code 200} HTML document, never a {@code 302}.</b>
 *       Crawlers follow redirects, so a redirect to the single-page app would land them on
 *       a page with no Open Graph tags and no server rendering — and the link would unfurl
 *       as the bare site title. Serving the metadata to everyone removes user-agent
 *       guessing from the path where a miss breaks something.</li>
 *   <li><b>{@code /s/**} is not registered on {@code RateLimitInterceptor}.</b> One paste
 *       into a busy workspace is a burst of unfurl fetches from one address range; a 429
 *       there breaks the preview rather than throttling an abuser. See {@code WebConfig}.</li>
 *   <li><b>Public without an explicit matcher.</b> {@code SecurityConfig} ends
 *       {@code .anyRequest().permitAll()}, so this is already reachable —
 *       {@code SecurityConfigTest} asserts it stays that way, because a future tightening
 *       would otherwise break every link already shared, silently.</li>
 * </ul>
 *
 * <p>{@code SecurityConfig}'s global cache-control disable applies here and is correct: a
 * cached share document would stop the click counter incrementing.
 */
@Controller
public class ShortLinkController {

  private static final Logger LOG = LoggerFactory.getLogger(ShortLinkController.class);

  private final ShortLinkService shortLinkService;
  private final ShareDocumentRenderer renderer;
  private final UnfurlerDetector unfurlerDetector;
  private final BlogRepository blogRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;

  public ShortLinkController(
      final ShortLinkService shortLinkService,
      final ShareDocumentRenderer renderer,
      final UnfurlerDetector unfurlerDetector,
      final BlogRepository blogRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository
  ) {
    this.shortLinkService = shortLinkService;
    this.renderer = renderer;
    this.unfurlerDetector = unfurlerDetector;
    this.blogRepository = blogRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
  }

  /**
   * Resolves a share address.
   *
   * @param slug the address
   * @param userAgent the requesting client, used only to decide whether to count a click
   * @return {@code 200} with the share document, or {@code 404} with the themed not-found
   *     page for an unknown slug
   */
  @GetMapping(value = "/s/{slug}", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> resolve(
      @PathVariable final String slug,
      @RequestHeader(value = "User-Agent", required = false) final String userAgent
  ) {
    Optional<ShortLink> link = shortLinkService.resolve(slug);
    if (link.isEmpty()) {
      return html(HttpStatus.NOT_FOUND, renderer.notFoundDocument());
    }

    Optional<ShareTarget> target = resolveTarget(link.get());
    if (target.isEmpty()) {
      // An orphaned link: the content it pointed at has been deleted. Better a themed
      // not-found than a share card describing something that no longer exists.
      // The slug reached us as a path variable. It matched a stored link, so it cannot
      // actually carry a newline — but that is a property of the data, not of this method,
      // and LogSafe is what the codebase uses to keep it that way.
      LOG.info("Short link '{}' points at missing {} {}",
          LogSafe.value(slug), link.get().contentType(), LogSafe.value(link.get().contentId()));
      return html(HttpStatus.NOT_FOUND, renderer.notFoundDocument());
    }

    if (!unfurlerDetector.isUnfurler(userAgent)) {
      shortLinkService.recordClick(slug);
    }

    return html(HttpStatus.OK, renderer.shareDocument(target.get()));
  }

  /**
   * Loads the content a link points at, flattened into the shape the renderer needs.
   *
   * <p>The three sources name their summary field differently — a blog has a
   * {@code shortDescription}, an article and an event have a {@code summary} — and an
   * event has no image at all, so it falls through to the committed share card.
   *
   * @return the target, or empty when the content has been deleted
   */
  private Optional<ShareTarget> resolveTarget(final ShortLink link) {
    String destination = link.contentType().destinationPath(link.contentId());
    return switch (link.contentType()) {
      case BLOG -> blogRepository.findById(link.contentId())
          .map(blog -> new ShareTarget(
              blog.title(), blog.shortDescription(), blog.featuredImageUrl(), destination));
      case ARTICLE -> articleRepository.findById(link.contentId())
          .map(article -> new ShareTarget(
              article.title(), article.summary(), article.imageUrl(), destination));
      case EVENT -> eventRepository.findById(link.contentId())
          .map(event -> new ShareTarget(
              event.title(), event.summary(), null, destination));
    };
  }

  private ResponseEntity<String> html(final HttpStatus status, final String body) {
    return ResponseEntity.status(status)
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .body(body);
  }
}
