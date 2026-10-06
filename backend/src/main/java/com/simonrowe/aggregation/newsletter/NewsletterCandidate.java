package com.simonrowe.aggregation.newsletter;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Every story the newsletter ingest has read, whether or not it reached the site.
 *
 * <p>This collection is the ingest's ledger as well as the review queue. A story scored below the
 * threshold never becomes an {@code aggregated_articles} row, so the article collection cannot
 * tell the next run it has already been read; without this row it would be re-scored, and
 * re-offered, every six hours until it fell out of the look-back window. The unique {@code url}
 * index created by {@code V051SeedTldrNewsletterSources} is what makes "read once" hold when two
 * editions carry the same story or two runs overlap. It is not an annotation, because
 * auto-index-creation is off.
 *
 * @param id Mongo id
 * @param sourceName the content source (edition) it came from, e.g. {@code TLDR Dev}
 * @param title the headline
 * @param url the canonical story address, unique
 * @param summary the newsletter's own summary, used as the article summary if it is saved
 * @param section the issue section it sat under
 * @param label the newsletter's label, e.g. {@code 5 minute read}
 * @param messageId the Gmail message it was read from
 * @param issueSubject that message's subject, so a reviewer can tell which issue it was
 * @param receivedAt when that message arrived
 * @param relevance best cosine similarity to any hearted article, 0 when nothing is hearted
 * @param nearestFavourite the title of the hearted article it was closest to, if any
 * @param status where it is in review
 * @param reason why it is in that status, in words a reviewer can act on
 * @param articleId the {@code aggregated_articles} id once saved
 * @param createdAt when it was read
 * @param decidedAt when its status last changed
 */
@Document(collection = NewsletterCandidate.COLLECTION)
public record NewsletterCandidate(
    @Id String id,
    String sourceName,
    String title,
    String url,
    String summary,
    String section,
    String label,
    String messageId,
    String issueSubject,
    Instant receivedAt,
    double relevance,
    String nearestFavourite,
    Status status,
    String reason,
    String articleId,
    Instant createdAt,
    Instant decidedAt
) {

  public static final String COLLECTION = "newsletter_candidates";

  /** Review state. */
  public enum Status {
    /** Scored at or above the threshold and saved as an article without anyone looking. */
    ACCEPTED,
    /** Waiting for a decision in the admin console. Never on the public site. */
    PENDING,
    /** Saved as an article by hand from the review queue. */
    PROMOTED,
    /** Declined by hand. Kept so the same story is never offered again. */
    DISMISSED
  }

  /**
   * This candidate in a new state.
   *
   * @param newStatus the status
   * @param newReason why
   * @param newArticleId the saved article, or null
   * @param at when the decision was made
   * @return a copy with the decision applied
   */
  public NewsletterCandidate decided(
      final Status newStatus, final String newReason, final String newArticleId,
      final Instant at) {
    return new NewsletterCandidate(id, sourceName, title, url, summary, section, label,
        messageId, issueSubject, receivedAt, relevance, nearestFavourite, newStatus, newReason,
        newArticleId, createdAt, at);
  }
}
