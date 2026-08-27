package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CveFixPrBodyRenderer;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic CVE-fix flow: skip when a pull request is already open, read the actionable
 * findings, have the agent bump the vulnerable dependencies, open one pull request, then poll CI
 * and feed failures back to the agent until it is green or the run gives up.
 *
 * <p>Every configured value the flow needs arrives on {@link CveFixRequest}. A
 * {@code @WorkflowImpl} is instantiated by the Temporal SDK rather than by Spring, so this class
 * cannot inject {@code CveFixProperties} and holds nothing but constants.
 */
@WorkflowImpl(taskQueues = CveFixTaskQueues.CVE_FIX)
public class CveFixWorkflowImpl implements CveFixWorkflow {

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final CveFixActivities networkActivities =
      Workflow.newActivityStub(
          CveFixActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private final CveFixActivities agentActivities =
      Workflow.newActivityStub(
          CveFixActivities.class,
          ActivityOptions.newBuilder()
              // proposeAndPush is a clone, an agent run bounded by factory.cvefix.agent.timeout
              // (15m default), a changed-path validation and a push, all in one activity call.
              // 30m leaves room for a slow clone and push on the Pi around that 15m.
              .setStartToCloseTimeout(Duration.ofMinutes(30))
              // Not 30s, and the claim that used to justify it was wrong: ProcessRunner
              // heartbeats every 10s only *while a child process runs*, and emits nothing for a
              // git command that finishes faster than that — so the gap between git commands is
              // exactly what 30s tripped on. It killed a one-file code review on the Pi
              // (PR #111) during clone. See CodeReviewWorkflowImpl for why 2m and not 1m.
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              // No retry: a second agent run costs the same tokens and would repeat any
              // half-applied edit. A failed attempt fails the run instead.
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  /**
   * The issue sink, on its own task queue.
   *
   * <p>Executed by whichever container polls {@code linear} — {@code software-factory}, which
   * alone holds {@code LINEAR_API_KEY}.
   *
   * <p>2m schedule-to-close, deliberately short. With {@code factory.linear.enabled} false nothing
   * polls this queue, and a misconfiguration must cost the run two minutes per component, not the
   * default. {@code linearFilingEnabled} on the request is the primary guard; this is the backstop.
   */
  private final LinearActivities linear =
      Workflow.newActivityStub(
          LinearActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(LinearTaskQueues.LINEAR)
              .setStartToCloseTimeout(Duration.ofSeconds(90))
              .setScheduleToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private CveFixProgress current = CveFixProgress.accepted();
  private String workflowId;
  private Instant startedAt;
  private int findingsSeen;
  private int ciAttempts;
  private List<String> bumpDescriptions = List.of();
  private int unfixableCount;
  private String prUrl;

  @Override
  public CveFixResult run(final CveFixRequest request) {
    workflowId = Workflow.getInfo().getWorkflowId();
    // Workflow time, never Instant.now(): the latter is non-deterministic and would differ on
    // every replay of this history.
    startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    long deadline = Workflow.currentTimeMillis() + request.maxWait().toMillis();
    try {
      current =
          new CveFixProgress(CveFixPhase.CHECKING_PR, "Checking for an open pull request", null);
      prUrl = networkActivities.findOpenPrUrl();
      if (prUrl != null) {
        // A distinct status on purpose: an open CVE pull request halts all CVE automation by
        // design, so a month of these must read as a stall rather than a month of clean runs.
        // No recordUnfixable here — no agent ran, so there is nothing new to record.
        current = new CveFixProgress(CveFixPhase.SKIPPED, "A CVE pull request is already open", 0);
        return finish(
            CveFixStatus.SKIPPED_PR_OPEN,
            "a CVE pull request is already open, so this run stopped");
      }

      current = new CveFixProgress(CveFixPhase.FETCHING, "Reading Dependency-Track findings", null);
      List<ComponentFindings> components = networkActivities.fetchActionableFindings();
      findingsSeen = components.stream().mapToInt(component -> component.findings().size()).sum();
      if (components.isEmpty()) {
        current = new CveFixProgress(CveFixPhase.COMPLETED, "Nothing actionable", 0);
        return finish(CveFixStatus.NO_FINDINGS, "Dependency-Track reported nothing actionable");
      }

      current =
          new CveFixProgress(
              CveFixPhase.PROPOSING, "Proposing dependency bumps", components.size());
      CveFixActivities.PushResult push =
          agentActivities.proposeAndPush(components, null, List.of());
      applySummary(push.summary());

      if (push.headSha() == null) {
        // FixProposal.isEmpty() is bumps.isEmpty(), so a run whose every component was declined
        // lands here with a populated unfixable list. Recording it is what stops a CVE with no
        // available fix costing an agent run every night.
        fileUnfixable(
            request, networkActivities.recordUnfixable(push.summary().unfixable(), components));
        current = new CveFixProgress(CveFixPhase.COMPLETED, "No bump was possible", 0);
        return finish(
            CveFixStatus.NOTHING_FIXABLE, "the agent produced no dependency bump it could push");
      }

      if (request.dryRun()) {
        // Stops here rather than before the push: ci.yml triggers on pull_request only, so the
        // branch alone runs nothing, and the diff is left on the branch for a human to look at.
        // DRY_RUN, not COMPLETED: the branch push and this recordUnfixable are both real side
        // effects, so the distinction has to be legible at a glance rather than only in detail().
        fileUnfixable(
            request, networkActivities.recordUnfixable(push.summary().unfixable(), components));
        current = new CveFixProgress(CveFixPhase.COMPLETED, "Dry run", bumpDescriptions.size());
        return finish(
            CveFixStatus.DRY_RUN,
            "dry run: pushed " + bumpDescriptions.size() + " bump(s), no pull request opened");
      }

      current =
          new CveFixProgress(
              CveFixPhase.PUSHING, "Opening the pull request", bumpDescriptions.size());
      CveFixPrGateway.OpenPullRequest pullRequest =
          networkActivities.openPullRequest(push.summary());
      if (pullRequest == null || pullRequest.number() <= 0) {
        // Without a number the give-up comment has nowhere to go and the CI loop would poll a
        // pull request nobody can see. ApplicationFailure, not IllegalStateException: see the
        // comment on the catch clause below.
        throw ApplicationFailure.newNonRetryableFailure(
            "The CVE pull request was not opened", "CVE_FIX_PR_NOT_OPENED");
      }
      prUrl = pullRequest.htmlUrl();

      return awaitCi(request, components, push, pullRequest, deadline);
    } catch (RuntimeException exception) {
      // Anything thrown from workflow code itself must be an ApplicationFailure (or another
      // TemporalFailure): a raw JDK exception is not recognised by this SDK version as a
      // deliberate business failure and manifests as an infinite workflow-task retry loop, with
      // the workflow never closing and getResult() blocking forever. See the same note in
      // ReviewFeedbackWorkflowImpl, which was written after reproducing exactly that.
      String message = safeFailureMessage(exception);
      current = new CveFixProgress(CveFixPhase.FAILED, message, findingsSeen);
      recordFailedRun(message);
      throw exception;
    }
  }

  @Override
  public CveFixProgress progress() {
    return current;
  }

  /**
   * Polls CI, repairing each red result by feeding the failure logs back to the agent, until CI is
   * green, the repair budget is spent, or the wall-clock cap elapses.
   *
   * <p>Both give-up paths end in {@link CveFixStatus#CI_UNRESOLVED} with the pull request left
   * open, because an operator treats them identically; only {@link CveFixResult#detail()}
   * distinguishes them.
   */
  private CveFixResult awaitCi(
      final CveFixRequest request,
      final List<ComponentFindings> components,
      final CveFixActivities.PushResult firstPush,
      final CveFixPrGateway.OpenPullRequest pullRequest,
      final long deadline) {
    String headSha = firstPush.headSha();
    CveFixActivities.FixSummary summary = firstPush.summary();
    // Every bump this run has already pushed and had CI reject, oldest first. Each attempt runs in
    // a brand-new shallow clone of the default branch, so nothing on disk tells the agent what the
    // last attempt declared; this list is the only channel. Accumulated rather than only carrying
    // the immediately previous attempt, so attempt three cannot re-propose what attempt one tried.
    // A LinkedHashSet keeps the order deterministic for replay while dropping repeats.
    Set<String> rejectedBumps = new LinkedHashSet<>(summary.bumpDescriptions());
    while (true) {
      // Workflow time, not System.currentTimeMillis(), and checked before the sleep so a run that
      // has already used its whole budget of wall clock stops instead of polling once more.
      if (Workflow.currentTimeMillis() >= deadline) {
        return giveUp(
            request,
            components,
            pullRequest,
            summary,
            "CI never went green within the "
                + describe(request.maxWait())
                + " wall-clock cap after "
                + ciAttempts
                + " repair attempt(s)");
      }

      current = new CveFixProgress(CveFixPhase.AWAITING_CI, "Waiting for CI", ciAttempts);
      // Workflow.sleep, never Thread.sleep: only the former is durable and deterministic.
      Workflow.sleep(request.pollInterval());
      CiOutcome outcome = networkActivities.checkCi(headSha);

      if (outcome.state() == CiOutcome.CiState.GREEN) {
        fileUnfixable(request, networkActivities.recordUnfixable(summary.unfixable(), components));
        current = new CveFixProgress(CveFixPhase.COMPLETED, "CI is green", ciAttempts);
        return finish(
            CveFixStatus.COMPLETED, "CI is green; the pull request is waiting for a human");
      }
      if (outcome.state() != CiOutcome.CiState.RED) {
        continue;
      }
      if (ciAttempts >= request.repairBudget()) {
        return giveUp(
            request,
            components,
            pullRequest,
            summary,
            "the repair budget of " + request.repairBudget() + " was exhausted with CI still red");
      }

      current = new CveFixProgress(CveFixPhase.REPAIRING, outcome.detail(), ciAttempts + 1);
      String failureLogs = networkActivities.ciFailureLogs(headSha);
      CveFixActivities.PushResult repair =
          agentActivities.proposeAndPush(components, failureLogs, List.copyOf(rejectedBumps));
      ciAttempts++;
      if (repair.headSha() == null) {
        // Nothing new was pushed, so CI would report the same red result for the same commit for
        // the rest of the budget. Stop now, keeping the previous attempt's summary, which is what
        // is actually on the branch.
        return giveUp(
            request,
            components,
            pullRequest,
            summary,
            "the agent produced no further change after "
                + ciAttempts
                + " repair attempt(s) with CI still red");
      }
      headSha = repair.headSha();
      summary = repair.summary();
      rejectedBumps.addAll(summary.bumpDescriptions());
      applySummary(summary);
    }
  }

  /**
   * Comments on the pull request, leaves it open for a human, records what stayed unfixable and
   * persists the run. {@code CveFixPhase} has no unresolved phase, so the phase is
   * {@code COMPLETED} and {@code detail} carries why the run stopped.
   *
   * @param request this run's settings, read only for the issue-sink flag
   * @param components the current finding set, whose fingerprints decide what is new information
   * @param pullRequest the pull request left open for a human
   * @param summary what is actually on the branch
   * @param detail why the run stopped
   * @return the terminal result
   */
  private CveFixResult giveUp(
      final CveFixRequest request,
      final List<ComponentFindings> components,
      final CveFixPrGateway.OpenPullRequest pullRequest,
      final CveFixActivities.FixSummary summary,
      final String detail) {
    networkActivities.commentOnPullRequest(
        pullRequest.number(), CveFixPrBodyRenderer.giveUpComment(summary, ciAttempts));
    fileUnfixable(request, networkActivities.recordUnfixable(summary.unfixable(), components));
    current = new CveFixProgress(CveFixPhase.COMPLETED, detail, ciAttempts);
    return finish(CveFixStatus.CI_UNRESOLVED, detail);
  }

  /** Persists the run record, then returns the result for this terminal status. */
  private CveFixResult finish(final CveFixStatus status, final String detail) {
    networkActivities.recordRun(
        new CveFixRunRecord(
            CveFixRunRecord.idFor(workflowId),
            workflowId,
            startedAt,
            status,
            findingsSeen,
            bumpDescriptions,
            prUrl,
            ciAttempts,
            detail));
    return new CveFixResult(
        workflowId, status, prUrl, bumpDescriptions.size(), unfixableCount, detail);
  }

  /**
   * Files one Linear issue per component this run newly recorded as unfixable.
   *
   * <p>Only the newly-recorded ones, which is why {@code recordUnfixable} reports them: the
   * schedule is daily and an unchanged finding set is the normal case, so filing everything stored
   * would comment on the same ticket every 24 hours forever.
   *
   * <p>Never changes the run's outcome. The suppression record is already written by the time this
   * is called, and the ticket is a nicety by comparison.
   *
   * @param request this run's settings, read only for the issue-sink flag
   * @param newlyRecorded the components {@code recordUnfixable} reported as new information
   */
  private void fileUnfixable(
      final CveFixRequest request, final List<UnfixableComponent> newlyRecorded) {
    // The flag first: with the sink disabled nothing polls the `linear` queue, so scheduling the
    // activity would stall the run until its schedule-to-close timeout rather than fail fast.
    if (!request.linearFilingEnabled() || newlyRecorded == null) {
      return;
    }
    for (UnfixableComponent component : newlyRecorded) {
      try {
        linear.fileIssue(
            new IssueFiling(
                "cvefix",
                // The component purl alone: the key UnfixableFindingRecord already uses, so one
                // ticket per component however many advisories accumulate against it.
                List.of(component.purl()),
                "Cannot auto-fix " + component.purl(),
                unfixableBody(component),
                "run " + Workflow.getInfo().getRunId(),
                // The run id PLUS the purl. One run files several components, and a bare run id
                // would make the second look like a replay of the first and be silently dropped.
                Workflow.getInfo().getRunId() + ":" + component.purl(),
                Workflow.getInfo().getWorkflowId()));
      } catch (RuntimeException exception) {
        // As wide as the sibling catch in recordFailedRun, and deliberately not just the
        // exhausted-activity type: encoding the IssueFiling payload happens on THIS thread, so a
        // converter error is not a TemporalFailure, would fail the workflow task, and Temporal
        // retries those forever — hanging the run and losing recordRun entirely.
        Workflow.getLogger(CveFixWorkflowImpl.class)
            .warn("Could not file {} into Linear", component.purl(), exception);
      }
    }
  }

  /**
   * The issue description for one component the agent declined to bump.
   *
   * <p>Static and side-effect-free, so a {@code @WorkflowImpl} may build it directly without
   * breaking determinism — the same reason {@link CveFixPrBodyRenderer#giveUpComment} is callable
   * from here.
   *
   * @param component the component the agent declined to bump
   * @return the rendered Markdown body
   */
  private static String unfixableBody(final UnfixableComponent component) {
    // Labelled as the agent's own view on purpose: UnfixableComponent's ids are model output,
    // and the authoritative set is the one FindingSuppressor stores from Dependency-Track. Here
    // they are context for a human, never a key - the purl is the key.
    String advisories =
        component.vulnerabilityIds().isEmpty()
            ? "none given"
            : String.join(", ", component.vulnerabilityIds());
    return "The CVE-fix agent declined to bump this component, so it is now suppressed and will "
        + "not cost another agent run until its Dependency-Track finding set changes.\n\n"
        + "- **Component:** `"
        + component.purl()
        + "`\n- **Advisories (as the agent reported them):** "
        + advisories
        + "\n- **Reason given:** "
        + component.reason()
        + "\n\nFixing this needs a human: either a manual bump, a code change to suit a new major "
        + "version, or a decision to accept the risk.\n";
  }

  /** Keeps the counts reported by the result and the run record on the latest pushed proposal. */
  private void applySummary(final CveFixActivities.FixSummary summary) {
    bumpDescriptions = summary.bumpDescriptions();
    unfixableCount = summary.unfixable().size();
  }

  /**
   * Best-effort: records the failed run so {@code cve_fix_runs} shows it, mirroring
   * {@code ReviewFeedbackWorkflowImpl.recordDistillationFailure}. A failure to record the failure
   * must not mask the real exception.
   */
  private void recordFailedRun(final String detail) {
    try {
      finish(CveFixStatus.FAILED, detail);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CveFixWorkflowImpl.class)
          .warn("Could not record the failed run", exception);
    }
  }

  /** Renders a duration the way the configuration writes it, so {@code 3h} reads as {@code 3h}. */
  private static String describe(final Duration duration) {
    long hours = duration.toHours();
    long minutes = duration.toMinutesPart();
    if (hours > 0) {
      return minutes > 0 ? hours + "h" + minutes + "m" : hours + "h";
    }
    return duration.toMinutes() + "m";
  }

  /**
   * Temporal wraps the cause in an {@link io.temporal.failure.ActivityFailure} whose own message is
   * boilerplate, so unwrap to the innermost message before truncating.
   */
  private static String safeFailureMessage(final RuntimeException exception) {
    String message = null;
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
        message = cause.getMessage();
      }
    }
    if (message == null) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
