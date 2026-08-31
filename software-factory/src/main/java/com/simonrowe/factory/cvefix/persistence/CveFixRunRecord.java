package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of one CVE-fix run: what it saw, what it changed, and how it ended.
 *
 * <p>{@code componentsSeen} counts {@code (Dependency-Track project, PURL)} pairs, not distinct
 * PURLs — one component present in two projects counts twice, because it is two entries in the
 * report and potentially two manifests to edit.
 */
@Document(collection = "cve_fix_runs")
public record CveFixRunRecord(
    @Id String id,
    String workflowId,
    Instant startedAt,
    CveFixStatus status,
    int findingsSeen,
    List<String> bumps,
    String prUrl,
    int ciAttempts,
    String detail,
    String runId,
    int componentsSeen,
    int filed,
    int updated,
    int suppressed,
    int regressed,
    List<String> issueUrls) {

  public CveFixRunRecord {
    bumps = bumps == null ? List.of() : List.copyOf(bumps);
    issueUrls = issueUrls == null ? List.of() : List.copyOf(issueUrls);
  }

  /** Backward-compatible constructor for records and tests written before issue-only scanning. */
  public CveFixRunRecord(
      final String id,
      final String workflowId,
      final Instant startedAt,
      final CveFixStatus status,
      final int findingsSeen,
      final List<String> bumps,
      final String prUrl,
      final int ciAttempts,
      final String detail) {
    this(id, workflowId, startedAt, status, findingsSeen, bumps, prUrl, ciAttempts, detail,
        null, 0, 0, 0, 0, 0, List.of());
  }

  /**
   * Deterministic id for upserts: one record per run, keyed by the Temporal workflow id, so a
   * re-drive of the same run overwrites its own row instead of adding a second one.
   */
  public static String idFor(final String workflowId) {
    return workflowId;
  }
}
