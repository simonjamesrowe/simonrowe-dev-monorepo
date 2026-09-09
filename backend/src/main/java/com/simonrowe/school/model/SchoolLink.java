package com.simonrowe.school.model;

import java.time.Instant;
import java.util.Locale;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A hyperlink found in school content, recorded but <b>never followed automatically</b>.
 *
 * <p>The ingester does not fetch these. An email can link anywhere — a tracking pixel, a
 * third-party portal, something a sender did not intend to share — and a crawler that follows
 * whatever arrives in a mailbox is a very different thing from one that reads a school website.
 * Each link waits here until someone looks at it and says fetch.
 *
 * @param id {@code sha256(sourceDocumentId + '|' + url)}, so re-ingesting the same message does
 *     not re-offer a link that has already been decided
 * @param sourceDocumentId the document the link was found in
 * @param url the absolute URL
 * @param anchorText the link's visible text, which is usually the best description available
 * @param discoveredAt when it was first seen
 * @param status what has been decided about it
 * @param fetchedDocumentId the document created by fetching it, when that has happened
 * @param failureReason why a fetch failed, when it did
 */
@Document(collection = "school_links")
public record SchoolLink(
    @Id String id,
    String sourceDocumentId,
    String url,
    String anchorText,
    Instant discoveredAt,
    Status status,
    String fetchedDocumentId,
    String failureReason
) {

  /** What has been decided about a discovered link. */
  public enum Status {
    /** Seen, not yet decided. Nothing has been requested from the far end. */
    PENDING,
    /** Fetched on request, and ingested. */
    FETCHED,
    /** Deliberately declined. Never offered again. */
    IGNORED,
    /** A fetch was attempted and failed. */
    FAILED
  }

  /** Defaults the status so a link can never arrive already-decided by accident. */
  public SchoolLink {
    status = status == null ? Status.PENDING : status;
  }

  /**
   * A best guess at what is on the other end, from the URL alone.
   *
   * <p>Deliberately a guess from the address rather than a HEAD request: asking the far end what
   * it is means contacting it, which is precisely what this type exists to avoid until someone
   * has approved it.
   *
   * @return a short human label
   */
  public String likelyKind() {
    final String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
    if (lower.contains(".pdf") || lower.contains("download.asp")) {
      return "PDF document";
    }
    if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp)(\\?.*)?$")) {
      return "Image";
    }
    if (lower.contains("docs.google.com/forms") || lower.contains("forms.gle")) {
      return "Google Form";
    }
    if (lower.contains("kilmorieschool.co.uk")) {
      return "School website page";
    }
    if (lower.contains("unsubscribe") || lower.contains("mailchi.mp")) {
      return "Mailing list link";
    }
    return "Web page";
  }
}
