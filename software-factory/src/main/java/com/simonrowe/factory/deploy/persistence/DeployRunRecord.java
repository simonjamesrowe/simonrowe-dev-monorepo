package com.simonrowe.factory.deploy.persistence;

import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of one deploy: what it deployed, what each phase did, and how it ended.
 *
 * <p>Without this, deploy history lives only inside Temporal's retention window and in container
 * logs that a deploy itself rotates.
 *
 * @param id see {@link #idFor(String)}
 * @param workflowId always {@code deploy-prod} — the id is fixed so deploys coalesce
 * @param sha the commit deployed
 * @param trigger {@code workflow_run} or {@code manual}
 * @param startedAt workflow time, never wall-clock time
 * @param finishedAt workflow time
 * @param status how it ended
 * @param phases every phase's outcome, in the order they ran
 * @param configSync what configuration sync decided
 * @param rollbackTaken whether a rollback was attempted
 * @param rollbackStatus how the rollback ended, or null when none was attempted
 * @param maintenancePageLeftUp true when the run ended with the site showing the maintenance page
 * @param issueUrl the tracked issue this run filed, <strong>in Linear</strong> — not a GitHub
 *     issue, which is what this field carried before the issue sink replaced that path. Null when
 *     the sink is disabled, when nothing failed, or when the filing itself failed
 * @param commitCommentUrl the comment posted on the deployed commit
 * @param detail one line summarising the run
 * @param linearFilingFailed true when the run failed, filing was attempted, and no ticket was
 *     filed - whatever the cause: the sink unreachable, the rendering of the ticket itself
 *     failing, or the payload not encoding. All of them are the same fact to a reader, which is
 *     that a failure went untracked. What this distinguishes is a failure nobody tried to file
 */
@Document(collection = "deploy_runs")
public record DeployRunRecord(
    @Id String id,
    String workflowId,
    String sha,
    String trigger,
    Instant startedAt,
    Instant finishedAt,
    DeployStatus status,
    List<PhaseOutcome> phases,
    SyncOutcome configSync,
    boolean rollbackTaken,
    DeployStatus rollbackStatus,
    boolean maintenancePageLeftUp,
    String issueUrl,
    String commitCommentUrl,
    String detail,
    boolean linearFilingFailed) {

  public DeployRunRecord {
    phases = phases == null ? List.of() : List.copyOf(phases);
  }

  /**
   * Deterministic id for upserts: the Temporal <em>run</em> id, so a re-drive of the same run
   * overwrites its own row rather than adding a second one.
   *
   * <p>Deliberately not the workflow id, which is how {@code CveFixRunRecord} does it. This
   * feature's workflow id is the fixed constant {@code deploy-prod} — that is what makes deploys
   * coalesce — so keying on it would collapse every deploy in history into one document, and the
   * second deploy would silently erase the first. The run id is unique per execution and stable
   * across replays, which is exactly what an upsert key needs.
   *
   * @param runId the Temporal run id
   * @return the document id
   */
  public static String idFor(final String runId) {
    return idFor(runId, 0);
  }

  /**
   * The id for the {@code attempt}-th deploy within one workflow execution.
   *
   * <p>A single execution can deploy more than once: if a newer commit is signalled while a
   * deploy is in flight, the workflow drains it and deploys again rather than dropping it. Both
   * attempts share a run id, so without the attempt suffix the second would overwrite the first
   * and the newer commit's deploy would be the only one in history.
   *
   * <p>Still idempotent under replay, because the attempt number is derived from the workflow's
   * own deterministic loop rather than from wall-clock time.
   *
   * @param runId the Temporal run id
   * @param attempt zero-based deploy attempt within this execution
   * @return the document id
   */
  public static String idFor(final String runId, final int attempt) {
    return attempt == 0 ? runId : runId + ":" + attempt;
  }
}
