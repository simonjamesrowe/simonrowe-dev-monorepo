package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persisted record of one CVE-fix run: what it saw, what it changed, and how it ended. */
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
    String detail) {

  public CveFixRunRecord {
    bumps = bumps == null ? List.of() : List.copyOf(bumps);
  }

  /**
   * Deterministic id for upserts: one record per run, keyed by the Temporal workflow id, so a
   * re-drive of the same run overwrites its own row instead of adding a second one.
   */
  public static String idFor(final String workflowId) {
    return workflowId;
  }
}
