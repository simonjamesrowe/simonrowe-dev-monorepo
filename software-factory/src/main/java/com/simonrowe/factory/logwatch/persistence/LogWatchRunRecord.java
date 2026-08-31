package com.simonrowe.factory.logwatch.persistence;

import com.simonrowe.factory.logwatch.domain.LogWatchStatus;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.domain.Trigger;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of one scan.
 *
 * <p>Exists so the console can follow a run. It is <strong>not</strong> a dedup store: Linear is
 * the source of truth for what has been filed, and {@code linear_issues} is an audit trail rather
 * than authoritative (established by 039). Tracking seen signatures here would be a second source
 * of truth for something Linear already knows.
 *
 * <p>The id is the Temporal <strong>run</strong> id, not the workflow id. The scheduled workflow
 * id is stable, so keying on it would collapse all history into one document — the mistake
 * {@code deploy_runs} documents.
 *
 * @param id the Temporal run id
 * @param workflowId the Temporal workflow id, for correlation
 * @param startedAt when the run began
 * @param completedAt when it ended; null while running
 * @param status the terminal status, or {@code RUNNING}
 * @param trigger what started it
 * @param windowStart the scanned window's start
 * @param windowEnd the scanned window's end
 * @param linesRead how many lines were read
 * @param truncated whether the read hit its line budget, so the window was only partly examined
 * @param containersSeen how many distinct containers produced lines
 * @param signaturesFound distinct problems surviving the minimum-occurrence filter
 * @param signaturesDropped how many were lost to the per-run cap
 * @param sourceHealth the source-health verdict
 * @param sourceEvidence the justification for that verdict
 * @param filedIssues Linear issue identifiers touched by this run
 * @param detail free-text diagnostics
 */
@Document(collection = "logwatch_runs")
public record LogWatchRunRecord(
    @Id String id,
    String workflowId,
    Instant startedAt,
    Instant completedAt,
    LogWatchStatus status,
    Trigger trigger,
    Instant windowStart,
    Instant windowEnd,
    int linesRead,
    boolean truncated,
    int containersSeen,
    int signaturesFound,
    int signaturesDropped,
    SourceHealth.Status sourceHealth,
    String sourceEvidence,
    List<String> filedIssues,
    String detail) {

  public LogWatchRunRecord {
    filedIssues = filedIssues == null ? List.of() : List.copyOf(filedIssues);
  }
}
