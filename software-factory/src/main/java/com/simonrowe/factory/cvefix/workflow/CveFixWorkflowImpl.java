package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.github.CveFixPrBodyRenderer;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
              // ProcessRunner heartbeats every 10s for the whole life of each child process, so
              // 30s detects a wedged agent without tripping on the gaps between git commands.
              .setHeartbeatTimeout(Duration.ofSeconds(30))
              // No retry: a second agent run costs the same tokens and would repeat any
              // half-applied edit. A failed attempt fails the run instead.
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
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
      CveFixActivities.PushResult push = agentActivities.proposeAndPush(components, null);
      applySummary(push.summary());

      if (push.headSha() == null) {
        // FixProposal.isEmpty() is bumps.isEmpty(), so a run whose every component was declined
        // lands here with a populated unfixable list. Recording it is what stops a CVE with no
        // available fix costing an agent run every night.
        networkActivities.recordUnfixable(push.summary().unfixable(), components);
        current = new CveFixProgress(CveFixPhase.COMPLETED, "No bump was possible", 0);
        return finish(
            CveFixStatus.NOTHING_FIXABLE, "the agent produced no dependency bump it could push");
      }

      if (request.dryRun()) {
        // Stops here rather than before the push: ci.yml triggers on pull_request only, so the
        // branch alone runs nothing, and the diff is left on the branch for a human to look at.
        // DRY_RUN, not COMPLETED: the branch push and this recordUnfixable are both real side
        // effects, so the distinction has to be legible at a glance rather than only in detail().
        networkActivities.recordUnfixable(push.summary().unfixable(), components);
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
    while (true) {
      // Workflow time, not System.currentTimeMillis(), and checked before the sleep so a run that
      // has already used its whole budget of wall clock stops instead of polling once more.
      if (Workflow.currentTimeMillis() >= deadline) {
        return giveUp(
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
        networkActivities.recordUnfixable(summary.unfixable(), components);
        current = new CveFixProgress(CveFixPhase.COMPLETED, "CI is green", ciAttempts);
        return finish(
            CveFixStatus.COMPLETED, "CI is green; the pull request is waiting for a human");
      }
      if (outcome.state() != CiOutcome.CiState.RED) {
        continue;
      }
      if (ciAttempts >= request.repairBudget()) {
        return giveUp(
            components,
            pullRequest,
            summary,
            "the repair budget of " + request.repairBudget() + " was exhausted with CI still red");
      }

      current = new CveFixProgress(CveFixPhase.REPAIRING, outcome.detail(), ciAttempts + 1);
      String failureLogs = networkActivities.ciFailureLogs(headSha);
      CveFixActivities.PushResult repair =
          agentActivities.proposeAndPush(components, failureLogs);
      ciAttempts++;
      if (repair.headSha() == null) {
        // Nothing new was pushed, so CI would report the same red result for the same commit for
        // the rest of the budget. Stop now, keeping the previous attempt's summary, which is what
        // is actually on the branch.
        return giveUp(
            components,
            pullRequest,
            summary,
            "the agent produced no further change after "
                + ciAttempts
                + " repair attempt(s) with CI still red");
      }
      headSha = repair.headSha();
      summary = repair.summary();
      applySummary(summary);
    }
  }

  /**
   * Comments on the pull request, leaves it open for a human, records what stayed unfixable and
   * persists the run. {@code CveFixPhase} has no unresolved phase, so the phase is
   * {@code COMPLETED} and {@code detail} carries why the run stopped.
   */
  private CveFixResult giveUp(
      final List<ComponentFindings> components,
      final CveFixPrGateway.OpenPullRequest pullRequest,
      final CveFixActivities.FixSummary summary,
      final String detail) {
    networkActivities.commentOnPullRequest(
        pullRequest.number(), CveFixPrBodyRenderer.giveUpComment(summary, ciAttempts));
    networkActivities.recordUnfixable(summary.unfixable(), components);
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
