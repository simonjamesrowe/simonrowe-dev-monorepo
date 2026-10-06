package com.simonrowe.aggregation.newsletter;

import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The newsletter review queue. Under {@code /api/admin}, so {@code SecurityConfig} restricts it
 * to {@code DEV_PORTAL_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/newsletter-candidates")
public class AdminNewsletterController {

  private static final int MAX_PAGE_SIZE = 100;

  private final NewsletterCandidateRepository candidateRepository;
  private final NewsletterReviewService reviewService;
  private final NewsletterProperties properties;

  public AdminNewsletterController(
      final NewsletterCandidateRepository candidateRepository,
      final NewsletterReviewService reviewService,
      final NewsletterProperties properties) {
    this.candidateRepository = candidateRepository;
    this.reviewService = reviewService;
    this.properties = properties;
  }

  /** Counts per status and the threshold in force, for the page's tabs and header. */
  public record Summary(Map<Status, Long> counts, double relevanceThreshold,
      int maxAcceptedPerRun) {
  }

  /**
   * Lists candidates in one status.
   *
   * @param status which queue; defaults to the stories waiting for review
   * @param source an edition to restrict to, or blank for all
   * @param sort {@code relevance} (best first, the default) or {@code received} (newest first)
   * @param page zero-based page
   * @param size page size, capped at 100
   * @return the page
   */
  @GetMapping
  public Page<NewsletterCandidate> list(
      @RequestParam(defaultValue = "PENDING") final Status status,
      @RequestParam(required = false) final String source,
      @RequestParam(defaultValue = "relevance") final String sort,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size) {
    final Sort order = "received".equals(sort)
        ? Sort.by(Sort.Direction.DESC, "receivedAt").and(Sort.by(Sort.Direction.DESC, "relevance"))
        : Sort.by(Sort.Direction.DESC, "relevance").and(Sort.by(Sort.Direction.DESC, "receivedAt"));
    final Pageable pageable = PageRequest.of(
        Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE), order.and(Sort.by("id")));
    return source == null || source.isBlank()
        ? candidateRepository.findByStatus(status, pageable)
        : candidateRepository.findByStatusAndSourceName(status, source.trim(), pageable);
  }

  @GetMapping("/summary")
  public Summary summary() {
    final Map<Status, Long> counts = new EnumMap<>(Status.class);
    for (Status status : Status.values()) {
      counts.put(status, candidateRepository.countByStatus(status));
    }
    return new Summary(counts, properties.relevanceThreshold(), properties.maxAcceptedPerRun());
  }

  @PostMapping("/{id}/promote")
  public ResponseEntity<NewsletterCandidate> promote(@PathVariable final String id) {
    return ResponseEntity.of(reviewService.promote(id));
  }

  @PostMapping("/{id}/dismiss")
  public ResponseEntity<NewsletterCandidate> dismiss(@PathVariable final String id) {
    return ResponseEntity.of(reviewService.dismiss(id));
  }

  @ExceptionHandler(NewsletterReviewService.InvalidDecisionException.class)
  public ResponseEntity<Map<String, String>> invalidDecision(
      final NewsletterReviewService.InvalidDecisionException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
  }
}
