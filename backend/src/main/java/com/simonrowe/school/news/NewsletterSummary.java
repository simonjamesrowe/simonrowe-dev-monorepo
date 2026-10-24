package com.simonrowe.school.news;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A short summary of one school newsletter, written once when the newsletter arrives.
 *
 * <p>Kept in its own collection rather than as a field on {@code SchoolDocument}, which every
 * ingest path rebuilds through its constructor. {@code contentHash} ties a summary to the exact
 * text it was written from: when the school edits a newsletter its hash changes, and the stale
 * summary stops being served until a new one is written.
 *
 * @param id the newsletter's {@code SchoolDocument} id
 * @param contentHash the document's content hash when this was written
 * @param headline a few words saying what the week was about
 * @param points the things a parent needs to know, most important first
 * @param model the model that wrote it
 * @param generatedAt when it was written
 */
@Document(collection = NewsletterSummary.COLLECTION)
public record NewsletterSummary(
    @Id String id,
    String contentHash,
    String headline,
    List<Point> points,
    String model,
    Instant generatedAt
) {

  /** The collection name, shared with the backup and restore lists. */
  public static final String COLLECTION = "school_newsletter_summaries";

  /** Defaults a null list, so a reader never has to check. */
  public NewsletterSummary {
    points = points == null ? List.of() : List.copyOf(points);
  }

  /**
   * One thing from the newsletter.
   *
   * @param text one or two sentences
   * @param yearGroups the year groups it concerns, empty for the whole school
   */
  public record Point(String text, List<String> yearGroups) {

    /** Defaults a null list. */
    public Point {
      yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
    }
  }
}
