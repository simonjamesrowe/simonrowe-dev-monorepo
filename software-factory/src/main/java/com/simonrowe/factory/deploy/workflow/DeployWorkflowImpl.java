package com.simonrowe.factory.deploy.workflow;

import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.domain.DeployResult;
import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic deploy flow.
 *
 * <p>{@code sync-config} → {@code maintenance-on} → {@code pull} → {@code recreate} → {@code
 * verify} → {@code maintenance-off} → {@code verify-public}, and on a verification failure the
 * rollback path in {@link #handleFailure}.
 *
 * <p>Every configured value arrives on {@link DeployRequest}: a {@code @WorkflowImpl} is
 * instantiated by the Temporal SDK rather than by Spring, so this class cannot inject {@code
 * DeployProperties} and holds nothing but constants.
 *
 * <p><strong>This class runs in both JVMs</strong> — {@code software-factory} and {@code deployer}
 * both register a workflow-task poller on the {@code deploy} queue, because {@code @WorkflowImpl}
 * classpath scanning is unconditional. That is safe precisely because everything here is
 * orchestration: it touches no socket, no filesystem and no credential. Only {@code
 * DeployActivitiesImpl}, which exists on the {@code deployer} alone, does any of that.
 */
@WorkflowImpl(taskQueues = DeployTaskQueues.DEPLOY)
public class DeployWorkflowImpl implements DeployWorkflow {

  /**
   * A failed phase is not an activity failure.
   *
   * <p>{@code runPhase} returns the exit code rather than throwing, so these retry options only
   * ever cover genuine infrastructure faults — the process failing to start, or the phase
   * exceeding its timeout. A deploy phase that legitimately fails goes straight to the rollback
   * path instead of being retried into a longer outage.
   */
  private static final RetryOptions INFRASTRUCTURE_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(5))
          .setMaximumInterval(Duration.ofSeconds(30))
          .setMaximumAttempts(3)
          .build();

  private static final RetryOptions NO_RETRY =
      RetryOptions.newBuilder().setMaximumAttempts(1).build();

  /** Bookkeeping and reporting: fast, and worth retrying on a transient Mongo or GitHub blip. */
  private final DeployActivities fast =
      Workflow.newActivityStub(
          DeployActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(INFRASTRUCTURE_RETRIES)
              .build());

  /**
   * The shell phases.
   *
   * <p>45m start-to-close against a 30m {@code factory.deploy.phase-timeout}, so the script's own
   * ceiling is what stops a phase and the activity timeout is only a backstop. {@code pull}
   * fetches three ARM images to a Raspberry Pi and {@code verify} allows the script's 420s settle
   * budget, so neither fits in a conventional activity timeout.
   *
   * <p>2m heartbeat, not 30s: {@code ProcessRunner} heartbeats every 10s only <em>while a child
   * process runs</em>, and the gap between the script's own commands is what a tighter timeout
   * trips on. The same 30s value killed a one-file code review on the Pi during clone.
   */
  private final DeployActivities phases =
      Workflow.newActivityStub(
          DeployActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(45))
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setRetryOptions(INFRASTRUCTURE_RETRIES)
              .build());

  /** The agent. No retry: a second run costs the same tokens and reads the same evidence. */
  private final DeployActivities agent =
      Workflow.newActivityStub(
          DeployActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(20))
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NO_RETRY)
              .build());

  private DeployProgress current = DeployProgress.accepted();

  /**
   * Set by the signal handler. Read after each attempt, so a merge landing mid-deploy is not lost.
   */
  private String requestedSha;

  private final List<PhaseOutcome> outcomes = new ArrayList<>();

  @Override
  public void deployRequested(final String sha) {
    if (sha != null && !sha.isBlank()) {
      requestedSha = sha;
    }
  }

  @Override
  public DeployProgress progress() {
    return current;
  }

  @Override
  public DeployResult run(final DeployRequest request) {
    String sha = request.sha();
    DeployResult result = null;
    int attempt = 0;

    // The drain loop. Signal-with-start delivers a signal alongside the start, so on the first
    // pass requestedSha already equals sha and this runs exactly once. It runs again only when a
    // NEWER commit was signalled while the previous attempt was in flight — which is what makes
    // "absorbed into the one in flight, or the one that starts next" true of the second half.
    while (sha != null) {
      result = deployOnce(request, sha, attempt);
      attempt++;
      if (requestedSha != null && !requestedSha.equals(sha)) {
        sha = requestedSha;
      } else {
        sha = null;
      }
    }
    return result;
  }

  private DeployResult deployOnce(
      final DeployRequest request, final String sha, final int attempt) {
    outcomes.clear();
    // Workflow time, never Instant.now(): the latter is non-deterministic and would differ on
    // every replay of this history.
    Instant startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    String runId = Workflow.getInfo().getRunId();
    String recordId = DeployRunRecord.idFor(runId, attempt);

    // 1. sync-config FIRST, so every later phase uses the newly-synced compose file and script.
    //    Each phase is a separate `bash restart-prod.sh <phase>` process, and git replaces files
    //    by rename rather than in place, so a phase can safely rewrite the script the next phase
    //    runs. That property is why sync-config is its own phase rather than a step inside one.
    SyncOutcome sync = syncConfiguration(request, sha);
    if (sync.decision() == SyncDecision.FAILED) {
      return finish(
          recordId, request, sha, startedAt, sync, DeployStatus.FAILED, false, null, true,
          "configuration sync failed before anything was changed");
    }

    // 2. The page goes up before anything is pulled or recreated, so a visitor never sees a
    //    half-updated site or a raw proxy error.
    if (!step(DeployPhase.MAINTENANCE_ON, request, null)) {
      return finish(
          recordId, request, sha, startedAt, sync, DeployStatus.FAILED, false, null, false,
          "could not raise the maintenance page");
    }

    // 3. Pull the exact commit's images and re-tag them to :latest, recording the previous image
    //    ids first so a rollback has somewhere to go.
    if (!step(DeployPhase.PULL, request, sha)) {
      return failWithoutDeploying(
          recordId, request, sha, startedAt, sync, "pulling the new images failed");
    }

    if (!step(DeployPhase.RECREATE, request, null)) {
      return handleFailure(
          recordId, request, sha, startedAt, sync, DeployPhase.RECREATE);
    }

    // 4. What can be checked while the page is up: the container settle loop and the four ops
    //    hostnames. www/api are behind the flag and return 503 by design, which the hostname
    //    check treats as a failure — correctly — so they wait for step 6.
    if (!step(DeployPhase.VERIFY, request, null)) {
      return handleFailure(recordId, request, sha, startedAt, sync, DeployPhase.VERIFY);
    }

    // 5. Page down.
    if (!step(DeployPhase.MAINTENANCE_OFF, request, null)) {
      return handleFailure(
          recordId, request, sha, startedAt, sync, DeployPhase.MAINTENANCE_OFF);
    }

    // 6. And only now the public hostnames.
    if (!step(DeployPhase.VERIFY_PUBLIC, request, null)) {
      return handleFailure(
          recordId, request, sha, startedAt, sync, DeployPhase.VERIFY_PUBLIC);
    }

    DeployStatus status =
        sync.decision().movedHead() || sync.decision() == SyncDecision.ALREADY_CURRENT
            ? DeployStatus.DEPLOYED
            : DeployStatus.DEPLOYED_IMAGES_ONLY;
    current = new DeployProgress(DeployPhase.VERIFY_PUBLIC, "Deployed", sha);
    return finish(
        recordId, request, sha, startedAt, sync, status, false, null, false,
        detailFor(status, sync));
  }

  private SyncOutcome syncConfiguration(final DeployRequest request, final String sha) {
    if (!request.syncConfig()) {
      // Images-only deploys, deliberately available without disabling the whole deployer.
      return SyncOutcome.disabled(sha);
    }
    current = new DeployProgress(DeployPhase.SYNC_CONFIG, "Syncing host configuration", sha);
    SyncOutcome sync = phases.syncConfig(sha, request.dryRun());
    outcomes.add(
        new PhaseOutcome(
            DeployPhase.SYNC_CONFIG,
            sync.decision() != SyncDecision.FAILED,
            sync.decision().movedHead() || sync.decision() == SyncDecision.ALREADY_CURRENT ? 0 : 2,
            sync.detail(),
            0L));
    return sync;
  }

  /** Runs a phase, records its outcome, and reports whether it succeeded. */
  private boolean step(
      final DeployPhase phase, final DeployRequest request, final String imageTag) {
    current = new DeployProgress(phase, "Running " + phase.argument(), request.sha());
    PhaseOutcome outcome = phases.runPhase(phase, imageTag, request.dryRun());
    outcomes.add(outcome);
    return outcome.succeeded();
  }

  /**
   * A failure before {@code recreate} ran, so nothing is running the new images yet.
   *
   * <p>The page still has to come down — leaving it up when nothing was actually changed would
   * take the site offline over a failed pull.
   */
  private DeployResult failWithoutDeploying(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync,
      final String detail) {
    boolean pageDown = step(DeployPhase.MAINTENANCE_OFF, request, null);
    return reportAndFinish(
        recordId, request, sha, startedAt, sync, DeployStatus.FAILED, false, null, !pageDown,
        DeployPhase.PULL, outputOf(DeployPhase.PULL), detail);
  }

  /**
   * The rollback path.
   *
   * <p>Order matters at every step:
   *
   * <ol>
   *   <li><b>maintenance-on, re-asserted.</b> {@code verify-public} runs with the page already
   *       down, so a failure there is a failure with the broken version publicly visible.
   *   <li><b>rollback-config before rollback.</b> Restoring the previous commit first is what
   *       makes the image rollback run the <em>previous</em> version of {@code restart-prod.sh} —
   *       which is exactly what matters when the thing that broke the deploy was a change to the
   *       script itself. Skipped when configuration sync never moved {@code HEAD}.
   *   <li><b>verify the rollback.</b> The restored version is checked by the same checks as the
   *       deploy. An unverified rollback is not a rollback.
   *   <li><b>the page comes down only if that verification passed.</b> If the rollback also
   *       failed, the page stays up — which is the correct outcome, and the one thing here that
   *       must never be "helpfully" changed.
   *   <li><b>triage, then report.</b> Last, because they are the only steps that cannot make the
   *       site worse, and because the report wants to state what the rollback did.
   * </ol>
   */
  private DeployResult handleFailure(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync,
      final DeployPhase failedPhase) {
    String failureOutput = outputOf(failedPhase);

    if (!request.rollbackEnabled()) {
      // An escape hatch for the case where rollback itself is the problem. The broken version
      // stays up and the page stays up with it, because the operator has explicitly said not to
      // touch it.
      current = new DeployProgress(failedPhase, "Failed; rollback disabled", sha);
      return reportAndFinish(
          recordId, request, sha, startedAt, sync, DeployStatus.ROLLBACK_DISABLED, false, null,
          true, failedPhase, failureOutput,
          failedPhase.argument() + " failed and rollback is disabled");
    }

    current = new DeployProgress(DeployPhase.MAINTENANCE_ON, "Rolling back", sha);
    step(DeployPhase.MAINTENANCE_ON, request, null);

    if (sync.decision().movedHead() && sync.previousSha() != null) {
      current = new DeployProgress(DeployPhase.ROLLBACK_CONFIG, "Restoring configuration", sha);
      outcomes.add(phases.rollbackConfig(sync.previousSha(), request.dryRun()));
    }

    current = new DeployProgress(DeployPhase.ROLLBACK, "Restoring the previous images", sha);
    boolean rolledBack = step(DeployPhase.ROLLBACK, request, null);
    boolean verified = rolledBack && step(DeployPhase.VERIFY, request, null);

    boolean pageLeftUp = true;
    DeployStatus rollbackStatus;
    if (verified) {
      rollbackStatus = DeployStatus.ROLLED_BACK;
      // Only now, and only because the rollback verified clean.
      pageLeftUp = !step(DeployPhase.MAINTENANCE_OFF, request, null);
      if (!pageLeftUp) {
        verified = step(DeployPhase.VERIFY_PUBLIC, request, null);
        if (!verified) {
          // The previous version does not serve publicly either. Put the page back rather than
          // leave a broken site exposed.
          step(DeployPhase.MAINTENANCE_ON, request, null);
          pageLeftUp = true;
          rollbackStatus = DeployStatus.ROLLBACK_FAILED;
        }
      }
    } else {
      rollbackStatus = DeployStatus.ROLLBACK_FAILED;
    }

    DeployStatus status =
        rollbackStatus == DeployStatus.ROLLED_BACK
            ? DeployStatus.ROLLED_BACK
            : DeployStatus.ROLLBACK_FAILED;
    return reportAndFinish(
        recordId, request, sha, startedAt, sync, status, true, rollbackStatus, pageLeftUp,
        failedPhase, failureOutput, detailForFailure(failedPhase, status));
  }

  /**
   * Diagnoses the failure, reports it, and persists the run.
   *
   * <p>Every step here is best-effort and none of them can fail the run: the deploy has already
   * ended one way or another, and losing the record of it because GitHub was unreachable would be
   * the worst possible trade.
   */
  private DeployResult reportAndFinish(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync,
      final DeployStatus status,
      final boolean rollbackTaken,
      final DeployStatus rollbackStatus,
      final boolean maintenancePageLeftUp,
      final DeployPhase failedPhase,
      final String failureOutput,
      final String detail) {
    DeployRunRecord provisional =
        record(
            recordId, request, sha, startedAt, sync, status, rollbackTaken, rollbackStatus,
            maintenancePageLeftUp, null, null, detail);

    current = new DeployProgress(DeployPhase.TRIAGE, "Diagnosing the failure", sha);
    String evidence = null;
    DeployActivities.Triage triage = null;
    try {
      evidence =
          fast.captureEvidence(failedPhase, failureOutput, sync.previousSha(), sha);
      triage = agent.triage(evidence);
    } catch (RuntimeException exception) {
      // Left null; the renderer copes, and the report still says what failed and what the
      // rollback did.
      triage = null;
    }

    current = new DeployProgress(DeployPhase.REPORT, "Reporting the failure", sha);
    DeployActivities.Report report = null;
    try {
      report = fast.report(provisional, triage, request.installationId());
    } catch (RuntimeException exception) {
      report = null;
    }

    if (evidence != null) {
      try {
        fast.discardEvidence(evidence);
      } catch (RuntimeException exception) {
        // A leftover scratch directory is bounded and harmless.
      }
    }

    DeployRunRecord finalRecord =
        record(
            recordId, request, sha, startedAt, sync, status, rollbackTaken, rollbackStatus,
            maintenancePageLeftUp,
            report == null ? null : report.issueUrl(),
            report == null ? null : report.commitCommentUrl(),
            detail);
    fast.recordRun(finalRecord);
    return new DeployResult(
        status, sha, sync.decision(), report == null ? null : report.issueUrl(), detail);
  }

  private String outputOf(final DeployPhase phase) {
    for (int index = outcomes.size() - 1; index >= 0; index--) {
      if (outcomes.get(index).phase() == phase) {
        return outcomes.get(index).detail();
      }
    }
    return "";
  }

  private static String detailForFailure(
      final DeployPhase failedPhase, final DeployStatus status) {
    if (status == DeployStatus.ROLLBACK_FAILED) {
      return failedPhase.argument()
          + " failed and the rollback did not verify - the maintenance page has been left up";
    }
    return failedPhase.argument() + " failed; rolled back to the previous version";
  }

  private String detailFor(final DeployStatus status, final SyncOutcome sync) {
    if (status == DeployStatus.DEPLOYED_IMAGES_ONLY) {
      return "deployed images only: " + sync.detail();
    }
    return "deployed";
  }

  /** Persists the run and returns its result. Every non-failure exit path goes through here. */
  private DeployResult finish(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync,
      final DeployStatus status,
      final boolean rollbackTaken,
      final DeployStatus rollbackStatus,
      final boolean maintenancePageLeftUp,
      final String detail) {
    DeployRunRecord built =
        record(
            recordId, request, sha, startedAt, sync, status, rollbackTaken, rollbackStatus,
            maintenancePageLeftUp, null, null, detail);

    // Reported on SUCCESS, deliberately: "deployed, but not all of it" must not be silent. That
    // half-applied state - new images running against the previous commit's compose file and
    // nginx conf - is the exact thing this feature exists to make impossible to miss.
    String commentUrl = null;
    if (status == DeployStatus.DEPLOYED_IMAGES_ONLY) {
      try {
        DeployActivities.Report report =
            fast.report(built, null, request.installationId());
        commentUrl = report == null ? null : report.commitCommentUrl();
      } catch (RuntimeException exception) {
        // Best effort. Losing the notice is bad; losing the deploy record would be worse.
        commentUrl = null;
      }
    }

    DeployRunRecord finalRecord =
        record(
            recordId, request, sha, startedAt, sync, status, rollbackTaken, rollbackStatus,
            maintenancePageLeftUp, null, commentUrl, detail);
    fast.recordRun(finalRecord);
    return new DeployResult(status, sha, sync.decision(), null, detail);
  }

  private DeployRunRecord record(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync,
      final DeployStatus status,
      final boolean rollbackTaken,
      final DeployStatus rollbackStatus,
      final boolean maintenancePageLeftUp,
      final String issueUrl,
      final String commitCommentUrl,
      final String detail) {
    return new DeployRunRecord(
        recordId,
        WORKFLOW_ID,
        sha,
        request.trigger(),
        startedAt,
        Instant.ofEpochMilli(Workflow.currentTimeMillis()),
        status,
        List.copyOf(outcomes),
        sync,
        rollbackTaken,
        rollbackStatus,
        maintenancePageLeftUp,
        issueUrl,
        commitCommentUrl,
        detail);
  }
}
