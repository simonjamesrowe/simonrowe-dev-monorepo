package com.simonrowe.factory.staticanalysis.persistence;

import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisStatus;
import com.simonrowe.factory.staticanalysis.domain.Trigger;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of one scan, so the console can follow a run.
 *
 * <p>Not a dedup store: Linear is the source of truth for what has been filed, exactly as for log
 * watch. The id is the Temporal <strong>run</strong> id, because the scheduled workflow id is
 * stable and keying on it would collapse all history into one document.
 *
 * @param id the Temporal run id
 * @param workflowId the Temporal workflow id, for correlation
 * @param startedAt when the run began
 * @param completedAt when it ended
 * @param status the terminal status
 * @param trigger what started it
 * @param health the analysis verdict
 * @param healthEvidence the justification for that verdict
 * @param analysedRevision the commit the analysis read was of, or null
 * @param analysedAt when that analysis ran, or null
 * @param issuesSeen open new-code issues read, before the severity floor
 * @param issuesBelowFloor code smells under the floor, not filed
 * @param groupsFound finding groups produced, before the per-run cap
 * @param groupsDropped finding groups the cap left unfiled
 * @param filedIssues Linear issues filed or refreshed by this run
 * @param resolvedIssues Linear issues this run closed, kept apart from {@code filedIssues}
 *     because the two mean opposite things
 * @param detail free-text diagnostics
 */
@Document(collection = "static_analysis_runs")
public record StaticAnalysisRunRecord(
    @Id String id,
    String workflowId,
    Instant startedAt,
    Instant completedAt,
    StaticAnalysisStatus status,
    Trigger trigger,
    AnalysisHealth.Status health,
    String healthEvidence,
    String analysedRevision,
    Instant analysedAt,
    int issuesSeen,
    int issuesBelowFloor,
    int groupsFound,
    int groupsDropped,
    List<String> filedIssues,
    List<String> resolvedIssues,
    String detail) {

  public StaticAnalysisRunRecord {
    filedIssues = filedIssues == null ? List.of() : List.copyOf(filedIssues);
    resolvedIssues = resolvedIssues == null ? List.of() : List.copyOf(resolvedIssues);
  }
}
