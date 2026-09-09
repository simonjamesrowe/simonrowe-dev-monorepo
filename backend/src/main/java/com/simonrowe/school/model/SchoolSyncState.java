package com.simonrowe.school.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per-source ingestion bookkeeping. One document per source.
 *
 * <p>A null {@code cursor} means "full sync required" and is a <b>normal operating state</b>, not
 * an error. Google explicitly declines to guarantee how long a Gmail {@code historyId} stays
 * usable, and serves a 404 once it has expired; treating that as a failure would wedge ingestion
 * permanently the first time the mailbox went quiet for long enough.
 *
 * @param id the source key, e.g. {@code gmail} or {@code website}
 * @param cursor provider-specific incremental marker, or null to force a full sync
 * @param lastSuccessAt when this source last completed a run
 * @param lastFailureAt when it last failed, or null
 * @param lastFailureReason why it last failed — this is where a revoked credential surfaces
 * @param pageEtags per-URL change markers, so unchanged pages are not re-fetched against a site
 *     that asks for a ten-second crawl delay
 */
@Document(collection = "school_sync_state")
public record SchoolSyncState(
    @Id String id,
    String cursor,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    String lastFailureReason,
    Map<String, String> pageEtags
) {

  /** Normalises a null etag map so callers never null-check it. */
  public SchoolSyncState {
    pageEtags = pageEtags == null ? Map.of() : Map.copyOf(pageEtags);
  }

  /**
   * Creates the initial state for a source that has never run.
   *
   * @param id the source key
   * @return a state with no cursor, which forces a full sync
   */
  public static SchoolSyncState initial(final String id) {
    return new SchoolSyncState(id, null, null, null, null, Map.of());
  }

  /**
   * Whether the next run must be a full sync rather than an incremental one.
   *
   * @return true when there is no usable cursor
   */
  public boolean requiresFullSync() {
    return cursor == null || cursor.isBlank();
  }
}
