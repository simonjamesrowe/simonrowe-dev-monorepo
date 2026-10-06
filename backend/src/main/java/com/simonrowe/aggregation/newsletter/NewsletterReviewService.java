package com.simonrowe.aggregation.newsletter;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.aggregation.ContentSource;
import com.simonrowe.aggregation.ContentSourceRepository;
import com.simonrowe.aggregation.newsletter.NewsletterCandidate.Status;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The decisions a reviewer makes in the admin queue.
 *
 * <p>Promoting saves the story exactly as an automatically accepted one would be saved: the
 * linked page is fetched for its image and date, and the newsletter's summary is kept. Only
 * promoting reaches the site, the search index or the embeddings, so a queued or dismissed story
 * costs nothing beyond the one embedding that scored it.
 */
@Service
public class NewsletterReviewService {

  private final NewsletterCandidateRepository candidateRepository;
  private final ContentSourceRepository sourceRepository;
  private final NewsletterIngestService ingestService;
  private final ContentAggregationAgent aggregationAgent;

  public NewsletterReviewService(
      final NewsletterCandidateRepository candidateRepository,
      final ContentSourceRepository sourceRepository,
      final NewsletterIngestService ingestService,
      final ContentAggregationAgent aggregationAgent) {
    this.candidateRepository = candidateRepository;
    this.sourceRepository = sourceRepository;
    this.ingestService = ingestService;
    this.aggregationAgent = aggregationAgent;
  }

  /** Raised when a decision does not apply to a candidate in its current state. */
  public static class InvalidDecisionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidDecisionException(final String message) {
      super(message);
    }
  }

  /**
   * Saves a queued or dismissed story as an article.
   *
   * @param id the candidate id
   * @return the candidate after the decision, or empty when there is no such candidate
   * @throws InvalidDecisionException when it is already on the site
   */
  public Optional<NewsletterCandidate> promote(final String id) {
    return candidateRepository.findById(id).map(candidate -> {
      if (candidate.status() == Status.ACCEPTED || candidate.status() == Status.PROMOTED) {
        throw new InvalidDecisionException("This story is already on the site");
      }
      final ContentSource source = sourceRepository.findByName(candidate.sourceName())
          .orElseThrow(() -> new InvalidDecisionException(
              "The source '%s' no longer exists".formatted(candidate.sourceName())));
      final Optional<String> articleId = aggregationAgent.saveCuratedArticle(
          source, ingestService.toContent(candidate), candidate.summary());
      return candidateRepository.save(candidate.decided(Status.PROMOTED,
          articleId.isPresent() ? "Promoted from the review queue"
              : "Already on the site from another source",
          articleId.orElse(null), Instant.now()));
    });
  }

  /**
   * Declines a queued story. It stays recorded, so it is never offered again.
   *
   * @param id the candidate id
   * @return the candidate after the decision, or empty when there is no such candidate
   * @throws InvalidDecisionException when it is not waiting for review
   */
  public Optional<NewsletterCandidate> dismiss(final String id) {
    return candidateRepository.findById(id).map(candidate -> {
      if (candidate.status() != Status.PENDING) {
        throw new InvalidDecisionException("Only a story waiting for review can be dismissed");
      }
      return candidateRepository.save(candidate.decided(
          Status.DISMISSED, "Dismissed from the review queue", null, Instant.now()));
    });
  }
}
