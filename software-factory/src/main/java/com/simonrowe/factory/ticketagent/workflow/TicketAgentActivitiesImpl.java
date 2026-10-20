package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.codereview.domain.AutoMergePolicy;
import com.simonrowe.factory.git.PathGlobs;
import com.simonrowe.factory.git.RepositoryWorkspace;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.ticketagent.agent.AgentEdit;
import com.simonrowe.factory.ticketagent.agent.TicketAgentEngine;
import com.simonrowe.factory.ticketagent.api.TicketAgentAccepted;
import com.simonrowe.factory.ticketagent.api.TicketAgentService;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import com.simonrowe.factory.ticketagent.domain.AttemptRequest;
import com.simonrowe.factory.ticketagent.domain.AttemptResult;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.simonrowe.factory.ticketagent.domain.SilencingDetector;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.domain.TicketAgentSettings;
import com.simonrowe.factory.ticketagent.domain.TicketBrief;
import com.simonrowe.factory.ticketagent.github.TicketAgentGitHub;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The ticket agent's side effects.
 *
 * <p>The {@code @ConditionalOnProperty} keeps every one of them on {@code software-factory}. The
 * {@code deployer} runs the same image and holds the Docker socket; it must never clone, run an
 * agent or push. The condition is evaluated by the component scanner, so declaring this class
 * through an explicit {@code @Bean} method would register it unconditionally and silently ignore
 * the annotation. {@code TicketAgentWorkerRegistrationTest} pins it by component-scanning.
 *
 * <p>Linear is called through {@link LinearGateway} directly, not through the {@code linear}
 * queue's activities: this class only ever runs in the container that holds the Linear key, so
 * there is no credential to keep away from anywhere, and the ticket operations here (labels,
 * named states) are the agent's own rather than the sink's.
 */
@Component
@ConditionalOnProperty(name = "factory.ticket-agent.enabled", havingValue = "true")
@ActivityImpl(taskQueues = TicketAgentTaskQueues.TICKET_AGENT)
public class TicketAgentActivitiesImpl implements TicketAgentActivities {

  private static final Logger LOGGER = LoggerFactory.getLogger(TicketAgentActivitiesImpl.class);

  /** Linear rejects very long comments, and a diff past this is not one a person reads anyway. */
  static final int MAX_DIFF_CHARS = 30_000;

  private final TicketAgentProperties properties;
  private final LinearGateway linear;
  private final TicketAgentGitHub gitHub;
  private final TicketAgentEngine engine;
  private final RepositoryWorkspaceFactory workspaces;
  private final TicketAgentService service;

  public TicketAgentActivitiesImpl(
      final TicketAgentProperties properties,
      final LinearGateway linear,
      final TicketAgentGitHub gitHub,
      final TicketAgentEngine engine,
      final RepositoryWorkspaceFactory workspaces,
      final TicketAgentService service) {
    this.properties = properties;
    this.linear = linear;
    this.gitHub = gitHub;
    this.engine = engine;
    this.workspaces = workspaces;
    this.service = service;
  }

  @Override
  public TicketAgentSettings settings() {
    return new TicketAgentSettings(
        properties.label(),
        properties.branchPrefix(),
        properties.maxAttempts(),
        properties.checksWait(),
        properties.reviewWait(),
        properties.mergeWait());
  }

  @Override
  public LinearTicket loadTicket(final String identifier) {
    return linearCall(() -> linear.ticket(identifier), identifier);
  }

  @Override
  public void claim(final String issueId, final String branch) {
    linearCall(
        () -> {
          linear.moveToState(issueId, properties.inProgressState());
          linear.addComment(
              issueId,
              """
              The software factory picked this up. It works on branch `%s` and opens a pull \
              request when it has a change. It cannot build or test on its own: GitHub Actions \
              does that, and it gets %d attempts to make CI and the reviewer happy.\
              """
                  .formatted(branch, properties.maxAttempts()));
          return null;
        },
        issueId);
  }

  @Override
  public AttemptResult attempt(final AttemptRequest request) {
    return attempt(request, detail -> Activity.getExecutionContext().heartbeat(detail));
  }

  AttemptResult attempt(final AttemptRequest request, final Consumer<String> heartbeat) {
    Long installationId = gitHub.installationId();
    // The first attempt starts from main; a repair builds on what the earlier attempts pushed.
    String cloneBranch = request.attempt() == 1 ? null : request.branch();
    try (RepositoryWorkspace workspace =
        workspaces.create(
            properties.owner(), properties.repository(), installationId,
            properties.workspaceRoot(), "ticket-agent-", cloneBranch, heartbeat)) {
      String brief =
          request.attempt() == 1
              ? TicketBrief.initial(
                  request.ticket(), properties.slug(), properties.maxAttempts(),
                  properties.deniedPaths())
              : TicketBrief.repair(
                  request.ticket(), properties.slug(), request.attempt(),
                  properties.maxAttempts(), properties.deniedPaths(), request.failure());
      AgentEdit edit = engine.attempt(workspace.repository(), brief, heartbeat);
      List<String> changed = workspaces.changedPaths(workspace, heartbeat);
      if (!edit.changed() || changed.isEmpty()) {
        return result(AttemptResult.Status.NO_CHANGE, null, edit, changed, List.of(), null);
      }
      List<String> denied = PathGlobs.matching(changed, properties.deniedPaths());
      if (!denied.isEmpty()) {
        return result(
            AttemptResult.Status.DENIED, null,
            new AgentEdit(
                true,
                "It changed %s, which the agent may never push.".formatted(
                    String.join(", ", denied)),
                edit.prTitle(), edit.prBody()),
            changed, List.of(), null);
      }
      String diff = workspaces.stagedDiff(workspace, heartbeat);
      List<String> silencing = SilencingDetector.reasons(diff);
      if (request.dryRun()) {
        return result(
            AttemptResult.Status.DRY_RUN, null, edit, changed, silencing,
            diff.length() <= MAX_DIFF_CHARS ? diff : diff.substring(0, MAX_DIFF_CHARS));
      }
      String headSha =
          workspaces.commitAndPush(
              workspace,
              request.branch(),
              commitMessage(edit, request),
              properties.gitAuthorName(),
              properties.gitAuthorEmail(),
              installationId,
              request.attempt() == 1,
              heartbeat);
      return result(AttemptResult.Status.PUSHED, headSha, edit, changed, silencing, null);
    }
  }

  private static AttemptResult result(
      final AttemptResult.Status status,
      final String headSha,
      final AgentEdit edit,
      final List<String> changed,
      final List<String> silencing,
      final String diff) {
    return new AttemptResult(
        status, headSha, edit.summary(), edit.prTitle(), edit.prBody(), changed, silencing, diff);
  }

  private static String commitMessage(final AgentEdit edit, final AttemptRequest request) {
    String title = edit.prTitle() == null || edit.prTitle().isBlank()
        ? "fix: resolve " + request.ticket().identifier()
        : edit.prTitle().trim();
    return request.attempt() == 1
        ? title
        : "fix: address CI and review feedback (attempt %d)".formatted(request.attempt());
  }

  @Override
  public PullRequestRef openPullRequest(
      final String issueId,
      final String branch,
      final String title,
      final String body,
      final String ticketUrl) {
    PullRequestRef pullRequest =
        gitHub.openPullRequest(
            branch,
            title == null || title.isBlank() ? "fix: resolve ticket" : title.trim(),
            """
            %s

            Opened by the software factory's ticket agent for %s."""
                .formatted(body == null ? "" : body.trim(), ticketUrl),
            gitHub.installationId());
    linearCall(
        () -> {
          // Retried activities must not stack identical attachments: attach only if this issue
          // does not already carry the URL.
          boolean attached =
              linear.issuesForFingerprint(pullRequest.url()).stream()
                  .anyMatch(issue -> issue.id().equals(issueId));
          if (!attached) {
            linear.attachUrl(issueId, pullRequest.url(), "Agent pull request");
          }
          return null;
        },
        issueId);
    return pullRequest;
  }

  @Override
  public void markSilencing(final int pullNumber, final boolean silencing) {
    gitHub.setLabel(pullNumber, AutoMergePolicy.OPT_OUT_LABEL, silencing, gitHub.installationId());
  }

  @Override
  public ChecksReport readChecks(final String headSha) {
    return gitHub.checks(headSha, gitHub.installationId());
  }

  @Override
  public String failureBrief(final ChecksReport checks, final int pullNumber) {
    return gitHub.failureBrief(checks, pullNumber, gitHub.installationId());
  }

  @Override
  public PullRequestState readPullRequest(final int pullNumber) {
    return gitHub.readPullRequest(pullNumber, gitHub.installationId());
  }

  @Override
  public void comment(final String issueId, final String body) {
    linearCall(
        () -> {
          linear.addComment(issueId, body);
          return null;
        },
        issueId);
  }

  @Override
  public void moveToReview(final String issueId, final String body) {
    linearCall(
        () -> {
          linear.addComment(issueId, body);
          linear.moveToState(issueId, properties.inReviewState());
          return null;
        },
        issueId);
  }

  @Override
  public void complete(final String issueId, final String body) {
    linearCall(
        () -> {
          String completed = linear.teamContext().completedStateId();
          linear.addComment(issueId, body);
          if (completed != null) {
            linear.updateIssue(issueId, null, completed);
          }
          return null;
        },
        issueId);
  }

  @Override
  public void handBack(
      final String issueId,
      final String body,
      final boolean stuck,
      final String pullRequestNodeId) {
    if (pullRequestNodeId != null) {
      gitHub.park(pullRequestNodeId, gitHub.installationId());
    }
    linearCall(
        () -> {
          linear.addComment(issueId, body);
          linear.removeLabel(issueId, properties.label());
          if (stuck) {
            linear.addLabel(issueId, properties.stuckLabel());
          }
          linear.moveToState(issueId, properties.returnState());
          return null;
        },
        issueId);
  }

  @Override
  public int dispatch() {
    List<LinearTicket> labelled =
        linearCall(() -> linear.openTicketsLabelled(properties.label()), properties.label());
    List<LinearTicket> claimed = new ArrayList<>();
    List<LinearTicket> waiting = new ArrayList<>();
    for (LinearTicket ticket : labelled) {
      if (ticket.hasLabel(properties.stuckLabel())
          || properties.inReviewState().equalsIgnoreCase(ticket.stateName())) {
        continue;
      }
      if (properties.inProgressState().equalsIgnoreCase(ticket.stateName())) {
        claimed.add(ticket);
      } else {
        waiting.add(ticket);
      }
    }
    int inFlight = 0;
    int started = 0;
    // Claimed tickets first. Each either has a live run, which counts against the cap, or lost it
    // (a crash after the claim), and starting it again is the recovery.
    for (LinearTicket ticket : claimed) {
      TicketAgentAccepted accepted = startRun(ticket);
      inFlight++;
      started += accepted.started() ? 1 : 0;
    }
    waiting.sort(INTAKE_ORDER);
    for (LinearTicket ticket : waiting) {
      if (inFlight >= properties.maxInFlight()) {
        break;
      }
      TicketAgentAccepted accepted = startRun(ticket);
      inFlight++;
      started += accepted.started() ? 1 : 0;
    }
    LOGGER.info(
        "Ticket intake: {} labelled, {} in flight, {} started", labelled.size(), inFlight, started);
    return started;
  }

  /**
   * Most urgent first, then oldest. Linear's priority 0 means "none", which sorts after low, not
   * before urgent.
   */
  static final Comparator<LinearTicket> INTAKE_ORDER =
      Comparator.comparingInt((LinearTicket ticket) ->
              ticket.priority() <= 0 ? Integer.MAX_VALUE : ticket.priority())
          .thenComparing(LinearTicket::createdAt);

  private TicketAgentAccepted startRun(final LinearTicket ticket) {
    return service.start(
        new TicketAgentRequest(ticket.identifier(), false, TicketAgentRequest.TRIGGER_INTAKE));
  }

  /**
   * Linear's retryable faults escape for Temporal to back off; anything else fails the activity
   * for good, the same translation the sink uses.
   */
  private static <T> T linearCall(final Supplier<T> action, final String subject) {
    try {
      return action.get();
    } catch (LinearApiException exception) {
      if (exception.retryable()) {
        throw exception;
      }
      throw ApplicationFailure.newNonRetryableFailureWithCause(
          exception.getMessage(), "LINEAR_API_ERROR", exception, subject);
    }
  }
}
