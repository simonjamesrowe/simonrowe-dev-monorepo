package com.simonrowe.factory.deploy.workflow;

import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployKind;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployPlan;
import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.domain.DeployResult;
import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.PhaseScope;
import com.simonrowe.factory.deploy.domain.PlanFacts;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic deploy flow.
 *
 * <p>{@code sync-config} → {@code plan} → {@code maintenance-on} → {@code pull} → {@code recreate}
 * → {@code verify} → {@code maintenance-off} → {@code verify-public}, and on a verification
 * failure the rollback path in {@link #handleFailure}.
 *
 * <p>{@code plan} decides the {@link DeployKind}: a deploy that changes nothing stops there, and
 * one that changes only the software factory skips both maintenance phases and the nginx restart.
 * Anything else, including a plan that could not be made, runs the full flow above.
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

  /**
   * The issue sink, on its own task queue.
   *
   * <p>Executed by {@code software-factory}, not by the {@code deployer} that runs every other
   * activity here — that is what keeps the tracker credential off the container holding the Docker
   * socket.
   *
   * <p>2m schedule-to-close, deliberately short. With {@code factory.linear.enabled} false nothing
   * polls this queue, and a misconfiguration must cost the deploy two minutes, not the default.
   * {@code linearFilingEnabled} on the request is the primary guard; this is the backstop.
   */
  private final LinearActivities linear =
      Workflow.newActivityStub(
          LinearActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(LinearTaskQueues.LINEAR)
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setScheduleToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(INFRASTRUCTURE_RETRIES)
              .build());

  private DeployProgress current = DeployProgress.accepted();

  /**
   * Set by the signal handler. Read after each attempt, so a merge landing mid-deploy is not lost.
   */
  private String requestedSha;

  private final List<PhaseOutcome> outcomes = new ArrayList<>();

  /**
   * The {@link Workflow#getVersion} change id guarding the plan phase.
   *
   * <p>The plan adds an activity call before {@code maintenance-on} and can remove later ones, so a
   * history recorded before it must replay without it. Deploys started on the old build get {@link
   * Workflow#DEFAULT_VERSION} and run exactly the old sequence.
   */
  static final String DEPLOY_PLAN_CHANGE = "deploy-plan";

  /** This pass's plan. Reset by every pass of the drain loop. */
  private DeployPlan plan = DeployPlan.everything(List.of(), "not planned yet");

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
    plan = DeployPlan.everything(request.services(), "not planned yet");
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

    // 2. What kind of deploy this is, decided before anything changes.
    plan = planDeploy(request, sha, sync);
    if (plan.kind() == DeployKind.NOTHING) {
      return finishWithNothingToDo(recordId, request, sha, startedAt, sync);
    }
    boolean page = plan.raisesMaintenancePage();

    // 3. The page goes up before anything is pulled or recreated, so a visitor never sees a
    //    half-updated site or a raw proxy error. Not for a factory-only deploy: nothing a visitor
    //    reaches is touched, and the page would be the only outage it caused.
    if (page && !step(DeployPhase.MAINTENANCE_ON, request, null)) {
      return finish(
          recordId, request, sha, startedAt, sync, DeployStatus.FAILED, false, null, false,
          "could not raise the maintenance page");
    }

    // 4. Pull the exact commit's images and re-tag them to :latest, recording the previous image
    //    ids first so a rollback has somewhere to go.
    if (!step(DeployPhase.PULL, request, sha)) {
      return failWithoutDeploying(
          recordId, request, sha, startedAt, sync, "pulling the new images failed");
    }

    if (!step(DeployPhase.RECREATE, request, null)) {
      return handleFailure(
          recordId, request, sha, startedAt, sync, DeployPhase.RECREATE);
    }

    // 5. What can be checked while the page is up: the container settle loop and the four ops
    //    hostnames. www/api are behind the flag and return 503 by design, which the hostname
    //    check treats as a failure — correctly — so they wait for step 7.
    if (!step(DeployPhase.VERIFY, request, null)) {
      return handleFailure(recordId, request, sha, startedAt, sync, DeployPhase.VERIFY);
    }

    // 6. Page down.
    if (page && !step(DeployPhase.MAINTENANCE_OFF, request, null)) {
      return handleFailure(
          recordId, request, sha, startedAt, sync, DeployPhase.MAINTENANCE_OFF);
    }

    // 7. And only now the public hostnames. Still run for a factory-only deploy: it is the check
    //    that the site is serving, and the point at which unused images are pruned.
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

  /**
   * Decides the kind of deploy, on a build that knows how.
   *
   * <p>A deploy started before this existed replays as {@link DeployKind#SITE} over every
   * service, with no {@code plan} activity: exactly what it scheduled the first time.
   */
  private DeployPlan planDeploy(
      final DeployRequest request, final String sha, final SyncOutcome sync) {
    int version = Workflow.getVersion(DEPLOY_PLAN_CHANGE, Workflow.DEFAULT_VERSION, 1);
    if (version == Workflow.DEFAULT_VERSION) {
      return DeployPlan.everything(request.services(), "planned by an older build");
    }

    current = new DeployProgress(DeployPhase.PLAN, "Working out what changed", sha);
    PlanFacts facts;
    long startedAt = Workflow.currentTimeMillis();
    try {
      // previousSha is non-null only when sync-config moved HEAD, which is exactly when
      // bind-mounted files can have changed underneath the running containers.
      facts = phases.plan(sync.previousSha(), sha, request.dryRun());
    } catch (RuntimeException exception) {
      // A plan that cannot be made is a full deploy, never a stopped one.
      facts = PlanFacts.unreadable("the plan activity failed: " + exception.getMessage());
    }
    DeployPlan decided = DeployPlan.decide(facts, request.services());
    outcomes.add(
        new PhaseOutcome(
            DeployPhase.PLAN,
            true,
            0,
            "%s: %s (%s)"
                .formatted(
                    decided.kind(),
                    decided.detail(),
                    facts == null ? "no facts" : facts.detail()),
            Workflow.currentTimeMillis() - startedAt));
    return decided;
  }

  /**
   * The exit for a commit that changes nothing running: a documentation-only merge.
   *
   * <p>{@link DeployStatus#SKIPPED}, unless configuration sync declined, in which case the run is
   * reported exactly as an images-only deploy is, so the held-back notice still reaches the
   * commit. Nothing changed only because the change that mattered was held back.
   */
  private DeployResult finishWithNothingToDo(
      final String recordId,
      final DeployRequest request,
      final String sha,
      final Instant startedAt,
      final SyncOutcome sync) {
    boolean declined =
        !sync.decision().movedHead()
            && sync.decision() != SyncDecision.ALREADY_CURRENT
            && sync.decision() != SyncDecision.DISABLED;
    DeployStatus status = declined ? DeployStatus.DEPLOYED_IMAGES_ONLY : DeployStatus.SKIPPED;
    current = new DeployProgress(DeployPhase.PLAN, "Nothing to deploy", sha);
    String detail =
        declined ? plan.detail() + "; configuration held back: " + sync.detail() : plan.detail();
    return finish(
        recordId, request, sha, startedAt, sync, status, false, null, false, detail);
  }

  /** Runs a phase, records its outcome, and reports whether it succeeded. */
  private boolean step(
      final DeployPhase phase, final DeployRequest request, final String imageTag) {
    current = new DeployProgress(phase, "Running " + phase.argument(), request.sha());
    PhaseScope scope = plan.scope();
    PhaseOutcome outcome =
        scope == null
            ? phases.runPhase(phase, imageTag, request.dryRun())
            : phases.runScopedPhase(phase, imageTag, request.dryRun(), scope);
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
    boolean pageDown =
        !plan.raisesMaintenancePage() || step(DeployPhase.MAINTENANCE_OFF, request, null);
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
    // A factory-only deploy never touched what a visitor reaches, so its failure path does not
    // put the site behind the page either: the rollback restores the factory's own containers.
    boolean page = plan.raisesMaintenancePage();

    if (!request.rollbackEnabled()) {
      // An escape hatch for the case where rollback itself is the problem. The broken version
      // stays up and the page stays up with it, because the operator has explicitly said not to
      // touch it.
      current = new DeployProgress(failedPhase, "Failed; rollback disabled", sha);
      return reportAndFinish(
          recordId, request, sha, startedAt, sync, DeployStatus.ROLLBACK_DISABLED, false, null,
          page, failedPhase, failureOutput,
          failedPhase.argument() + " failed and rollback is disabled");
    }

    current = new DeployProgress(DeployPhase.MAINTENANCE_ON, "Rolling back", sha);
    if (page) {
      step(DeployPhase.MAINTENANCE_ON, request, null);
    }

    if (sync.decision().movedHead() && sync.previousSha() != null) {
      current = new DeployProgress(DeployPhase.ROLLBACK_CONFIG, "Restoring configuration", sha);
      outcomes.add(phases.rollbackConfig(sync.previousSha(), request.dryRun()));
    }

    current = new DeployProgress(DeployPhase.ROLLBACK, "Restoring the previous images", sha);
    boolean rolledBack = step(DeployPhase.ROLLBACK, request, null);
    boolean verified = rolledBack && step(DeployPhase.VERIFY, request, null);

    boolean pageLeftUp = page;
    DeployStatus rollbackStatus;
    if (verified) {
      rollbackStatus = DeployStatus.ROLLED_BACK;
      // Only now, and only because the rollback verified clean.
      if (page) {
        pageLeftUp = !step(DeployPhase.MAINTENANCE_OFF, request, null);
      }
      if (!pageLeftUp) {
        verified = step(DeployPhase.VERIFY_PUBLIC, request, null);
        if (!verified) {
          // The previous version does not serve publicly either. Put the page back rather than
          // leave a broken site exposed - but only if this deploy is the one that took it down.
          // A factory-only deploy did not touch the site, so hiding it behind the page would
          // hide a fault this deploy did not cause.
          if (page) {
            step(DeployPhase.MAINTENANCE_ON, request, null);
            pageLeftUp = true;
          }
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
        failedPhase, failureOutput, detailForFailure(failedPhase, status, pageLeftUp));
  }

  /**
   * Diagnoses the failure, files it, reports it, and persists the run.
   *
   * <p>Reached only from the failure paths — {@link #failWithoutDeploying} and {@link
   * #handleFailure} — so there is no success case to exclude here; {@link #finish} is the exit for
   * a deploy that worked. That is why the issue sink needs no status guard: everything that
   * arrives here is something worth a ticket.
   *
   * <p>Every step here is best-effort and none of them can fail the run: the deploy has already
   * ended one way or another, and losing the record of it because GitHub or Linear was unreachable
   * would be the worst possible trade.
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
            maintenancePageLeftUp, null, null, detail, false);

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

    // The ticket BEFORE the comment: the comment names the ticket, so the ticket has to exist
    // first.
    String linearIssueUrl = null;
    boolean linearFilingFailed = false;
    if (request.linearFilingEnabled()) {
      current = new DeployProgress(DeployPhase.REPORT, "Filing the failure", sha);
      try {
        // Rendering happens in an activity, not here: a @WorkflowImpl must stay deterministic and
        // holds no Spring bean, so it can reach neither DeployReportRenderer nor the phase output.
        // Inside the try with the filing itself, deliberately: an exhausted render would otherwise
        // escape and fail the whole workflow, losing the run record over a piece of markdown.
        DeployActivities.Rendered rendered = fast.renderFailure(provisional, triage);
        FiledIssue filed =
            linear.fileIssue(
                new IssueFiling(
                    "deploy",
                    // Structured enum values already in scope: no parsing, no agent prose. Two
                    // phrasings of one failure must not become two tickets, which is why the
                    // headline is deliberately absent. Commit excluded too - see the design.
                    List.of(failedPhase.name().toLowerCase(Locale.ROOT), status.name()),
                    rendered.title(),
                    rendered.body(),
                    "commit " + sha + ", workflow run " + Workflow.getInfo().getRunId(),
                    // The run id PLUS the commit, exactly as cvefix appends the purl. ONE run can
                    // file more than once: the drain loop in run() re-enters deployOnce when a
                    // newer commit was signalled mid-deploy, and every pass presents the same run
                    // id. A bare run id would make the second failure look like a replay of the
                    // first to LinearIssueRecord.hasOccurrence, and IssueFiler would silently
                    // return the earlier decision without commenting or filing. The loop only
                    // re-enters when the signalled sha differs from the one just deployed, so the
                    // pair is distinct per pass - and if an operator re-signals a commit this run
                    // already deployed, collapsing that onto the earlier occurrence is the right
                    // reading anyway: same run, same commit, same problem.
                    Workflow.getInfo().getRunId() + ":" + sha,
                    Workflow.getInfo().getWorkflowId()));
        // Null-guarded rather than dereferenced. The catch below would contain the NPE, but "no
        // URL came back" is honestly a filing that returned nothing rather than one that failed.
        linearIssueUrl = filed == null ? null : filed.issueUrl();
      } catch (RuntimeException exception) {
        // As wide as the three sibling catches in this method, and deliberately not just the
        // exhausted-activity type: encoding the IssueFiling payload happens on THIS thread, so a
        // converter error is not a TemporalFailure, would fail the workflow task, and Temporal
        // retries those forever - hanging the deploy and losing recordRun entirely. The tracker
        // is not allowed to change the deploy's outcome by any route.
        Workflow.getLogger(DeployWorkflowImpl.class)
            .warn("Could not file the deploy failure into Linear", exception);
        linearFilingFailed = true;
      }
    }

    current = new DeployProgress(DeployPhase.REPORT, "Reporting the failure", sha);
    DeployActivities.Report report = null;
    try {
      report = fast.report(provisional, triage, request.installationId(), linearIssueUrl);
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
            linearIssueUrl,
            report == null ? null : report.commitCommentUrl(),
            detail,
            linearFilingFailed);
    fast.recordRun(finalRecord);
    return new DeployResult(status, sha, sync.decision(), linearIssueUrl, detail);
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
      final DeployPhase failedPhase, final DeployStatus status, final boolean pageLeftUp) {
    if (status == DeployStatus.ROLLBACK_FAILED) {
      return pageLeftUp
          ? "%s failed and the rollback did not verify - the maintenance page has been left up"
              .formatted(failedPhase.argument())
          : "%s failed and the rollback did not verify".formatted(failedPhase.argument());
    }
    return failedPhase.argument() + " failed; rolled back to the previous version";
  }

  private String detailFor(final DeployStatus status, final SyncOutcome sync) {
    boolean factoryOnly = plan.kind() == DeployKind.FACTORY;
    if (status == DeployStatus.DEPLOYED_IMAGES_ONLY) {
      return (factoryOnly ? "deployed the software factory, images only: "
          : "deployed images only: ") + sync.detail();
    }
    return factoryOnly ? "deployed the software factory; the site was left up" : "deployed";
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
            maintenancePageLeftUp, null, null, detail, false);

    // Reported on SUCCESS, deliberately: "deployed, but not all of it" must not be silent. That
    // half-applied state - new images running against the previous commit's compose file and
    // nginx conf - is the exact thing this feature exists to make impossible to miss.
    //
    // Commented on, but never filed: the site is up and the deploy did what it said it did, so
    // this is a notice rather than a defect, and a ticket per held-back deploy would be noise.
    String commentUrl = null;
    if (status == DeployStatus.DEPLOYED_IMAGES_ONLY) {
      try {
        DeployActivities.Report report =
            fast.report(built, null, request.installationId(), null);
        commentUrl = report == null ? null : report.commitCommentUrl();
      } catch (RuntimeException exception) {
        // Best effort. Losing the notice is bad; losing the deploy record would be worse.
        commentUrl = null;
      }
    }

    String scanDetail = scheduleLogWatchScan(request, sha, status, detail);

    DeployRunRecord finalRecord =
        record(
            recordId, request, sha, startedAt, sync, status, rollbackTaken, rollbackStatus,
            maintenancePageLeftUp, null, commentUrl, scanDetail, false);
    fast.recordRun(finalRecord);
    return new DeployResult(status, sha, sync.decision(), null, scanDetail);
  }

  /**
   * Schedules a log scan five minutes after a successful deploy, and can never affect the deploy.
   *
   * <p>FR-012 is absolute: this must not fail, delay or roll back a deploy that has already
   * verified green. Three things enforce it, and all three are needed.
   *
   * <ol>
   *   <li>The flag is checked before the activity is scheduled at all. With log watch disabled
   *       nothing polls its queue, so an unguarded schedule would stall the deploy until
   *       schedule-to-close rather than failing in milliseconds — the same reasoning as
   *       {@code linearFilingEnabled}.
   *   <li>It runs on the {@code fast} stub, so it is bounded by a short timeout and cannot hang.
   *   <li>Every failure is caught and recorded in the deploy's own detail. A log scan has no
   *       business turning a successful deploy into a failed one.
   * </ol>
   *
   * <p>Only the success statuses schedule anything. {@code DEPLOYED_IMAGES_ONLY} counts: a
   * held-back config sync still put new images into production, so it is still a change worth
   * watching. A deploy that failed and rolled back schedules nothing — the window would describe
   * the rollback rather than the change.
   *
   * @return the detail to record, with a note appended when scheduling was attempted
   */
  private String scheduleLogWatchScan(
      final DeployRequest request, final String sha, final DeployStatus status,
      final String detail) {
    boolean succeeded =
        status == DeployStatus.DEPLOYED || status == DeployStatus.DEPLOYED_IMAGES_ONLY;
    // NOTHING can still end DEPLOYED_IMAGES_ONLY, to carry a held-back notice, but it changed
    // nothing, so a scan of the next five minutes would be a scan of a deploy that did not happen.
    if (!succeeded || !request.logWatchTriggerEnabled() || plan.kind() == DeployKind.NOTHING) {
      return detail;
    }
    try {
      String scanId =
          fast.scheduleLogWatchScan(
              Instant.ofEpochMilli(Workflow.currentTimeMillis()),
              // The run id alone is not unique: the drain loop deploys a commit signalled
              // mid-deploy in the SAME run, and its scan collided with the first pass's, still
              // waiting out its start delay, so the second commit was never scanned (two merges
              // two minutes apart on 2026-10-24). The commit makes each pass's scan its own, and
              // an activity retry of one pass still gets the same id, so it stays idempotent.
              // Only an argument changes, so replaying an older history is unaffected.
              Workflow.getInfo().getRunId() + "-" + sha.substring(0, Math.min(12, sha.length())),
              request.linearFilingEnabled());
      return detail + "; log scan " + scanId + " scheduled in 5 minutes";
    } catch (RuntimeException exception) {
      // Recorded, never propagated. Losing the scan is a missed observation; failing the deploy
      // over it would be a self-inflicted outage.
      String cause =
          exception.getMessage() == null || exception.getMessage().isBlank()
              ? exception.getClass().getSimpleName()
              : exception.getMessage();
      return detail + "; post-deploy log scan could not be scheduled: " + cause;
    }
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
      final String detail,
      final boolean linearFilingFailed) {
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
        detail,
        linearFilingFailed);
  }
}
