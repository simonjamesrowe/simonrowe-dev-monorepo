package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.codereview.domain.AutoMergePolicy;
import com.simonrowe.factory.cvefix.domain.NpmRepairTitle;
import com.simonrowe.factory.git.DependencyPins;
import com.simonrowe.factory.git.NpmLockPins;
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
import com.simonrowe.factory.ticketagent.npm.NpmLockfileRepair;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
  private final NpmLockfileRepair npmRepair;

  public TicketAgentActivitiesImpl(
      final TicketAgentProperties properties,
      final LinearGateway linear,
      final TicketAgentGitHub gitHub,
      final TicketAgentEngine engine,
      final RepositoryWorkspaceFactory workspaces,
      final TicketAgentService service,
      final NpmLockfileRepair npmRepair) {
    this.properties = properties;
    this.linear = linear;
    this.gitHub = gitHub;
    this.engine = engine;
    this.workspaces = workspaces;
    this.service = service;
    this.npmRepair = npmRepair;
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
    Optional<NpmRepairTitle.Parsed> npm = npmRepair(request.ticket());
    if (npm.isPresent()) {
      return npmAttempt(request, npm.get(), heartbeat);
    }
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

  /**
   * The npm repair a ticket asks for: a vulnerability repair the scan filed, titled in the scan's
   * npm format. Anything else, including an npm ticket a person wrote by hand, goes to the agent.
   */
  static Optional<NpmRepairTitle.Parsed> npmRepair(final LinearTicket ticket) {
    return ticket != null && ticket.hasLabel(TicketAgentProperties.VULNERABILITY_LABEL)
        ? NpmRepairTitle.parse(ticket.title())
        : Optional.empty();
  }

  /**
   * An npm vulnerability repair, made without the agent: GitHub Actions regenerates the lockfile,
   * {@link NpmLockfileRepair} checks it, and this pushes it.
   *
   * <p>One attempt only. Regenerating the lockfile again gives the same lockfile, so a red build
   * on it is a person's problem, and the later attempts hand it back instead of repeating it.
   */
  private AttemptResult npmAttempt(
      final AttemptRequest request,
      final NpmRepairTitle.Parsed repair,
      final Consumer<String> heartbeat) {
    if (request.attempt() > 1) {
      return npmNoChange(
          """
          The lockfile came from `npm update` and CI or the reviewer still rejected it. \
          Regenerating it would give the same lockfile, so this needs a person.""");
    }
    Long installationId = gitHub.installationId();
    try (RepositoryWorkspace workspace =
        workspaces.create(
            properties.owner(), properties.repository(), installationId,
            properties.workspaceRoot(), "ticket-agent-", null, heartbeat)) {
      Path lockfile = workspace.repository().resolve(NpmLockPins.LOCKFILE);
      String base = Files.isRegularFile(lockfile) ? Files.readString(lockfile) : null;
      NpmLockfileRepair.Outcome outcome;
      try {
        outcome = npmRepair.run(repair, base, installationId, heartbeat);
      } catch (IllegalStateException exception) {
        return npmNoChange("The npm lockfile repair could not run: " + exception.getMessage());
      }
      if (!outcome.pushable()) {
        return npmNoChange(outcome.reason());
      }
      Files.writeString(lockfile, outcome.lockfile());
      List<String> changed = workspaces.changedPaths(workspace, heartbeat);
      if (!changed.equals(List.of(NpmLockPins.LOCKFILE))) {
        return npmNoChange("The repair changed %s, not only the lockfile."
            .formatted(String.join(", ", changed)));
      }
      AgentEdit edit = npmEdit(repair, outcome, request.ticket());
      String diff = workspaces.stagedDiff(workspace, heartbeat);
      List<String> silencing = SilencingDetector.reasons(diff);
      if (request.dryRun()) {
        return result(
            AttemptResult.Status.DRY_RUN, null, edit, changed, silencing,
            diff.length() <= MAX_DIFF_CHARS ? diff : diff.substring(0, MAX_DIFF_CHARS));
      }
      String headSha =
          workspaces.commitAndPush(
              workspace, request.branch(), edit.prTitle(), properties.gitAuthorName(),
              properties.gitAuthorEmail(), installationId, true, heartbeat);
      return result(AttemptResult.Status.PUSHED, headSha, edit, changed, silencing, null);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read or write the lockfile", exception);
    }
  }

  private static AttemptResult npmNoChange(final String reason) {
    return result(
        AttemptResult.Status.NO_CHANGE, null, new AgentEdit(false, reason, null, null),
        List.of(), List.of(), null);
  }

  /** The pull request for an npm repair, written from what actually moved. */
  static AgentEdit npmEdit(
      final NpmRepairTitle.Parsed repair,
      final NpmLockfileRepair.Outcome outcome,
      final LinearTicket ticket) {
    String to = outcome.changes().stream()
        .filter(change -> change.name().equals(repair.name()))
        .map(NpmLockPins.Change::to)
        .findFirst()
        .orElse(repair.fixedVersion());
    StringBuilder table = new StringBuilder("| Package | From | To |\n|---|---|---|\n");
    for (NpmLockPins.Change change : outcome.changes()) {
      table.append("| `%s` | %s | %s |\n".formatted(change.name(), change.from(), change.to()));
    }
    String title = "fix: update npm package %s to %s".formatted(repair.name(), to);
    String body = """
        Fixes %s: `%s` needed %s or later.

        %s
        Only `%s` changed. GitHub Actions regenerated it with \
        `npm update %s --package-lock-only --ignore-scripts` on the default branch (%s), and the \
        factory checked it before pushing: nothing added or removed, every moved package goes up \
        within its major version to the npm registry's own tarball, and `%s` reached %s in every \
        copy. `npm update` also moves the package's own dependencies within their ranges, which \
        is why more than one row can appear.

        No agent wrote this change."""
        .formatted(ticket.identifier(), repair.name(), repair.fixedVersion(), table,
            NpmLockPins.LOCKFILE, repair.name(), outcome.runUrl(), repair.name(),
            repair.fixedVersion());
    String summary = "Regenerated `%s`: %d package(s) moved, `%s` now %s."
        .formatted(NpmLockPins.LOCKFILE, outcome.changes().size(), repair.name(), to);
    return new AgentEdit(true, summary, title, body);
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
    markMergePolicy(pullNumber, silencing, false);
  }

  @Override
  public void markMergePolicy(
      final int pullNumber, final boolean silencing, final boolean vulnerabilityRepair) {
    Long installationId = gitHub.installationId();
    // Protect the PR before reading paths; a failed or truncated read must leave it manual.
    gitHub.setLabel(pullNumber, AutoMergePolicy.OPT_OUT_LABEL, true, installationId);
    // Read the whole PR on every attempt: a Java-only repair must not remove the label from a
    // branch whose earlier attempt changed a dependency. Include the old side of a rename too.
    // The head is read before the listing and again after it (in versionsOnly), so the diff and
    // the file contents judged together are provably from one commit.
    final String head = gitHub.headSha(pullNumber, installationId);
    List<DependencyPins.FileDiff> files = gitHub.changedFiles(pullNumber, installationId);
    List<String> paths = new ArrayList<>();
    for (DependencyPins.FileDiff file : files) {
      paths.add(file.path());
      if (file.previousPath() != null && !file.previousPath().isEmpty()) {
        paths.add(file.previousPath());
      }
    }
    List<String> dependencies =
        PathGlobs.matching(paths, TicketAgentProperties.MANUAL_MERGE_PATHS);
    if (silencing) {
      if (!dependencies.isEmpty()) {
        gitHub.noteDependencyReview(pullNumber, installationId);
      }
      return;
    }
    if (dependencies.isEmpty()) {
      gitHub.setLabel(pullNumber, AutoMergePolicy.OPT_OUT_LABEL, false, installationId);
      return;
    }
    if (vulnerabilityRepair
        && versionsOnly(pullNumber, head, files, dependencies, installationId)) {
      // The reviewer re-checks the same diff itself before arming; this only stops the agent
      // from vetoing a merge the review rules would allow.
      gitHub.setLabel(pullNumber, AutoMergePolicy.OPT_OUT_LABEL, false, installationId);
      gitHub.noteVersionBump(pullNumber, installationId);
      return;
    }
    gitHub.noteDependencyReview(pullNumber, installationId);
  }

  /**
   * Whether every dependency file in the PR is a pin file or the npm lockfile, and every change
   * in them only moves versions. {@code package.json} never qualifies: an npm repair regenerates
   * the lockfile alone.
   */
  private boolean versionsOnly(
      final int pullNumber,
      final String head,
      final List<DependencyPins.FileDiff> files,
      final List<String> dependencies,
      final Long installationId) {
    if (!dependencies.stream()
        .allMatch(path -> DependencyPins.isPinFile(path) || NpmLockPins.isLockfile(path))) {
      return false;
    }
    if (head == null || head.isBlank()
        || !head.equals(gitHub.headSha(pullNumber, installationId))) {
      LOGGER.info("PR #{} moved while its files were read; leaving it for a person", pullNumber);
      return false;
    }
    List<DependencyPins.FileDiff> pins = files.stream()
        .filter(file -> DependencyPins.isPinFile(file.path())
            || DependencyPins.isPinFile(file.previousPath()))
        .toList();
    if (dependencies.stream().anyMatch(DependencyPins::isPinFile)) {
      DependencyPins.Verdict verdict =
          DependencyPins.check(pins, path -> gitHub.pinFileAt(path, head, installationId));
      if (!verdict.versionsOnly()) {
        LOGGER.info("PR #{} needs a person to merge: {}", pullNumber, verdict.reason());
        return false;
      }
    }
    if (dependencies.stream().anyMatch(NpmLockPins::isLockfile)) {
      NpmLockPins.Verdict verdict =
          NpmLockPins.check(
              gitHub.lockfileAt(gitHub.baseSha(pullNumber, installationId), installationId),
              gitHub.lockfileAt(head, installationId));
      if (!verdict.versionsOnly()) {
        LOGGER.info("PR #{} needs a person to merge: {}", pullNumber, verdict.reason());
        return false;
      }
    }
    return true;
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
