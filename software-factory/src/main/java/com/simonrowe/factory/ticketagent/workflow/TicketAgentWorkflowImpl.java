package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import com.simonrowe.factory.ticketagent.domain.AttemptRequest;
import com.simonrowe.factory.ticketagent.domain.AttemptResult;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.ChecksSignal;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.simonrowe.factory.ticketagent.domain.TicketAgentOutcome;
import com.simonrowe.factory.ticketagent.domain.TicketAgentPhase;
import com.simonrowe.factory.ticketagent.domain.TicketAgentProgress;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.domain.TicketAgentResult;
import com.simonrowe.factory.ticketagent.domain.TicketAgentSettings;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Claim, attempt, wait for GitHub, repair, and finish on a merge or a hand-back.
 *
 * <p>Deterministic: every fact from outside (the ticket, the settings, what CI and the reviewer
 * said) arrives through an activity or a signal, so a replay takes the same path.
 *
 * <p>The signals only wake the run. What it acts on is always re-read from GitHub with {@link
 * TicketAgentActivities#readChecks}: a webhook can arrive twice, late, or for a commit the agent
 * has already replaced, and the reviewer's verdict reaches no webhook this App receives. A wait
 * that times out reads GitHub too, so a lost delivery costs a delay, never a stuck ticket.
 */
@WorkflowImpl(taskQueues = TicketAgentTaskQueues.TICKET_AGENT)
public class TicketAgentWorkflowImpl implements TicketAgentWorkflow {

  /** How often the reviewer's check is re-read once CI is green and the review is still running. */
  static final Duration REVIEW_POLL = Duration.ofMinutes(2);

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(2))
          .setMaximumInterval(Duration.ofSeconds(30))
          .setMaximumAttempts(4)
          .build();

  private final TicketAgentActivities network =
      Workflow.newActivityStub(
          TicketAgentActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(3))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  /**
   * One agent run: a clone, up to {@code factory.ticket-agent.agent.timeout} (30m) of agent time
   * and a push, on a Pi. 45m covers that; recompute it if the agent timeout is raised, because
   * workflow code cannot read configuration.
   */
  private final TicketAgentActivities agent =
      Workflow.newActivityStub(
          TicketAgentActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(45))
              // ProcessRunner heartbeats every 10s while a child process runs; the gap is the git
              // work around it. See CodeReviewWorkflowImpl for why 2m.
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  private final Map<String, ChecksSignal> checks = new HashMap<>();
  private Boolean closedMerged;
  private String stopReason;
  private TicketAgentProgress current = TicketAgentProgress.accepted();

  private String identifier;
  private boolean dryRun;
  private String title;
  private String ticketUrl;

  @Override
  public TicketAgentResult run(final TicketAgentRequest request) {
    identifier = request.identifier();
    dryRun = request.dryRun();
    TicketAgentSettings settings = network.settings();
    LinearTicket ticket = network.loadTicket(request.identifier());
    if (ticket == null || !ticket.stateType().open()
        || (!request.dryRun() && !ticket.hasLabel(settings.label()))) {
      update(TicketAgentPhase.HANDED_BACK, "Not eligible: open and labelled `%s` is required"
          .formatted(settings.label()), 0, null);
      return new TicketAgentResult(
          request.identifier(), TicketAgentOutcome.NOT_ELIGIBLE, 0, null, current.detail());
    }
    title = ticket.title();
    ticketUrl = ticket.url();
    String branch = settings.branchPrefix() + request.identifier().toLowerCase(Locale.ROOT);

    if (!request.dryRun()) {
      update(TicketAgentPhase.CLAIMING, "Claiming the ticket", 0, null);
      network.claim(ticket.id(), branch);
    }

    PullRequestRef pullRequest = null;
    String failure = null;
    boolean green = false;
    int attempt = 0;
    while (attempt < settings.maxAttempts()) {
      attempt++;
      if (stopReason != null) {
        return handBack(ticket, pullRequest, attempt - 1, stopped(), false);
      }
      update(
          attempt == 1 ? TicketAgentPhase.IMPLEMENTING : TicketAgentPhase.REPAIRING,
          attempt == 1
              ? "Attempt 1 of %d: making the change".formatted(settings.maxAttempts())
              : "Attempt %d of %d: fixing what failed".formatted(attempt, settings.maxAttempts()),
          attempt, url(pullRequest));
      AttemptResult result;
      try {
        result = agent.attempt(
            new AttemptRequest(ticket, branch, attempt, failure, request.dryRun()));
      } catch (ActivityFailure exception) {
        handBack(ticket, pullRequest, attempt,
            "The agent failed on attempt %d (%s), so this goes back to a person."
                .formatted(attempt, rootMessage(exception)), true);
        throw ApplicationFailure.newNonRetryableFailureWithCause(
            "Ticket agent attempt failed: " + rootMessage(exception), "AGENT_FAILED", exception);
      }

      switch (result.status()) {
        case DRY_RUN -> {
          network.comment(ticket.id(), dryRunComment(result));
          update(TicketAgentPhase.DRY_RUN, "Dry run: the would-be diff is on the ticket",
              attempt, null);
          return new TicketAgentResult(
              identifier, TicketAgentOutcome.DRY_RUN, attempt, null, current.detail());
        }
        case NO_CHANGE -> {
          if (request.dryRun()) {
            network.comment(
                ticket.id(), "Dry run: the agent would change nothing. " + result.summary());
            update(TicketAgentPhase.DRY_RUN, "Dry run: no change", attempt, null);
            return new TicketAgentResult(
                identifier, TicketAgentOutcome.DRY_RUN, attempt, null, current.detail());
          }
          return handBack(ticket, pullRequest, attempt,
              (attempt == 1
                  ? "The agent decided no change to the repository is warranted: %s"
                  : "The agent could not find a fix for what failed: %s")
                  .formatted(result.summary()),
              attempt > 1);
        }
        case DENIED -> {
          return handBack(ticket, pullRequest, attempt,
              "The change needs a path the agent may never push. " + result.summary(), true);
        }
        case PUSHED -> {
          if (pullRequest == null) {
            pullRequest = network.openPullRequest(
                ticket.id(), branch, result.prTitle(), result.prBody(), ticket.url());
            network.comment(ticket.id(),
                "Opened %s. %s".formatted(pullRequest.url(), result.summary()));
          } else {
            network.comment(ticket.id(),
                "Attempt %d pushed to %s. %s"
                    .formatted(attempt, pullRequest.url(), result.summary()));
          }
          boolean silencing = !result.silencing().isEmpty();
          if (Workflow.getVersion("vulnerability-merge-policy", Workflow.DEFAULT_VERSION, 1)
              == Workflow.DEFAULT_VERSION) {
            network.markSilencing(pullRequest.number(), silencing);
          } else {
            network.markMergePolicy(pullRequest.number(), silencing,
                ticket.hasLabel(TicketAgentProperties.VULNERABILITY_LABEL));
          }
        }
        default -> throw new IllegalStateException("Unknown attempt status " + result.status());
      }

      update(TicketAgentPhase.WAITING_FOR_CHECKS,
          "Attempt %d of %d: waiting for CI and the reviewer"
              .formatted(attempt, settings.maxAttempts()),
          attempt, pullRequest.url());
      ChecksReport report = awaitChecks(result.headSha(), settings);
      if (closedMerged != null) {
        return finishClosed(ticket, pullRequest, attempt);
      }
      if (stopReason != null) {
        return handBack(ticket, pullRequest, attempt, stopped(), false);
      }
      if (report.complete() && report.passed()) {
        green = true;
        break;
      }
      if (!report.ciComplete()) {
        return handBack(ticket, pullRequest, attempt,
            "CI did not finish on %s within %s, so this goes back to a person."
                .formatted(shortSha(result.headSha()), settings.checksWait()), true);
      }
      if (report.ciPassed() && !report.reviewComplete()) {
        return handBack(ticket, pullRequest, attempt,
            "CI passed but the code reviewer did not report on %s within %s."
                .formatted(shortSha(result.headSha()), settings.reviewWait()), true);
      }
      failure = network.failureBrief(report, pullRequest.number());
    }

    if (!green) {
      return handBack(ticket, pullRequest, attempt,
          """
          %d attempts and CI or the reviewer still rejects the change. The pull request is \
          left as a draft for a person: %s"""
              .formatted(attempt, url(pullRequest)),
          true);
    }
    return awaitMerge(ticket, pullRequest, attempt, settings);
  }

  /**
   * Waits for CI on one commit, then for the reviewer.
   *
   * <p>A red CI ends the wait at once: there is no point waiting on a review of a commit the next
   * attempt will replace.
   */
  private ChecksReport awaitChecks(final String headSha, final TicketAgentSettings settings) {
    Workflow.await(
        settings.checksWait(),
        () -> checks.containsKey(headSha) || stopReason != null || closedMerged != null);
    ChecksReport report = network.readChecks(headSha);
    long deadline = Workflow.currentTimeMillis() + settings.reviewWait().toMillis();
    while (report.ciComplete() && report.ciPassed() && !report.reviewComplete()
        && stopReason == null && closedMerged == null
        && Workflow.currentTimeMillis() < deadline) {
      Workflow.await(REVIEW_POLL, () -> stopReason != null || closedMerged != null);
      report = network.readChecks(headSha);
    }
    return report;
  }

  private TicketAgentResult awaitMerge(
      final LinearTicket ticket,
      final PullRequestRef pullRequest,
      final int attempts,
      final TicketAgentSettings settings) {
    PullRequestState state = network.readPullRequest(pullRequest.number());
    if (state.merged() || closedMerged != null) {
      return finishClosed(ticket, pullRequest, attempts);
    }
    network.moveToReview(ticket.id(),
        state.autoMergeArmed()
            ? """
              CI and the reviewer passed %s. Auto-merge is armed, so it merges once every \
              review conversation is resolved."""
                .formatted(pullRequest.url())
            : """
              CI and the reviewer passed %s, but it needs a person to merge it. The review \
              summary on the pull request says why auto-merge was not armed."""
                .formatted(pullRequest.url()));
    update(TicketAgentPhase.AWAITING_MERGE,
        state.autoMergeArmed() ? "Green; auto-merge armed" : "Green; needs a person to merge",
        attempts, pullRequest.url());
    Workflow.await(settings.mergeWait(), () -> closedMerged != null || stopReason != null);
    if (closedMerged == null && stopReason == null) {
      PullRequestState later = network.readPullRequest(pullRequest.number());
      if (later.merged()) {
        closedMerged = true;
      } else {
        update(TicketAgentPhase.IN_REVIEW, "Green, unmerged after " + settings.mergeWait(),
            attempts, pullRequest.url());
        return new TicketAgentResult(identifier, TicketAgentOutcome.IN_REVIEW, attempts,
            pullRequest.url(), current.detail());
      }
    }
    if (closedMerged == null) {
      return handBack(ticket, pullRequest, attempts, stopped(), false);
    }
    return finishClosed(ticket, pullRequest, attempts);
  }

  private TicketAgentResult finishClosed(
      final LinearTicket ticket, final PullRequestRef pullRequest, final int attempts) {
    if (Boolean.TRUE.equals(closedMerged)
        || network.readPullRequest(pullRequest.number()).merged()) {
      network.complete(ticket.id(), "Merged %s.".formatted(pullRequest.url()));
      update(TicketAgentPhase.MERGED, "Merged", attempts, pullRequest.url());
      return new TicketAgentResult(identifier, TicketAgentOutcome.MERGED, attempts,
          pullRequest.url(), current.detail());
    }
    return handBack(ticket, null, attempts,
        "%s was closed without merging.".formatted(pullRequest.url()), false);
  }

  private TicketAgentResult handBack(
      final LinearTicket ticket,
      final PullRequestRef pullRequest,
      final int attempts,
      final String reason,
      final boolean stuck) {
    if (dryRun) {
      // A dry run changes no ticket state: it says what a real run would have done, and stops.
      network.comment(ticket.id(), "Dry run: a real run would hand this back. " + reason);
      update(TicketAgentPhase.DRY_RUN, "Dry run: would hand back", attempts, null);
      return new TicketAgentResult(identifier, TicketAgentOutcome.DRY_RUN, attempts, null, reason);
    }
    network.handBack(ticket.id(), reason, stuck, pullRequest == null ? null : pullRequest.nodeId());
    update(TicketAgentPhase.HANDED_BACK, "Handed back: " + reason, attempts, url(pullRequest));
    return new TicketAgentResult(identifier, TicketAgentOutcome.HANDED_BACK, attempts,
        url(pullRequest), reason);
  }

  @Override
  public void checksCompleted(final ChecksSignal signal) {
    if (signal != null && signal.headSha() != null) {
      checks.put(signal.headSha(), signal);
    }
  }

  @Override
  public void pullRequestClosed(final boolean merged) {
    closedMerged = merged;
  }

  @Override
  public void stop(final String reason) {
    stopReason = reason == null || reason.isBlank() ? "Stopped from the console" : reason;
  }

  @Override
  public TicketAgentProgress progress() {
    return current;
  }

  /**
   * Records progress for the query, and in the run's memo for the console's run list.
   *
   * <p>The memo is what lets the drawer show each run's phase and pull request straight from one
   * visibility listing, rather than querying every run (a query on a closed run is a replay on the
   * Pi's worker, ten times a refresh).
   */
  private void update(
      final TicketAgentPhase phase, final String detail, final int attempt, final String prUrl) {
    current = new TicketAgentProgress(phase, detail, attempt, prUrl);
    Map<String, Object> memo = new LinkedHashMap<>();
    memo.put("ticket", identifier);
    memo.put("title", title == null || title.isBlank() ? identifier : identifier + " · " + title);
    memo.put("phase", phase.name());
    memo.put("detail", detail);
    memo.put("attempt", attempt);
    memo.put("url", prUrl != null ? prUrl : ticketUrl == null ? "" : ticketUrl);
    Workflow.upsertMemo(memo);
  }

  private String stopped() {
    return stopReason + ". The pull request, if any, is left as a draft.";
  }

  private static String dryRunComment(final AttemptResult result) {
    StringBuilder body = new StringBuilder("Dry run: this is the change the agent would push.\n\n")
        .append(result.summary()).append("\n\n");
    if (!result.silencing().isEmpty()) {
      body.append("It quiets something, so a person would have to merge it: ")
          .append(String.join("; ", result.silencing())).append(".\n\n");
    }
    return body.append("```diff\n").append(result.diff()).append("\n```").toString();
  }

  private static String url(final PullRequestRef pullRequest) {
    return pullRequest == null ? null : pullRequest.url();
  }

  private static String shortSha(final String sha) {
    return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }

  private static String rootMessage(final Throwable exception) {
    String message = null;
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
        message = cause.getMessage();
      }
    }
    String value = message == null ? exception.getClass().getSimpleName() : message;
    return value.length() > 300 ? value.substring(0, 300) : value;
  }
}
