package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.cvefix.agent.FixEngine;
import com.simonrowe.factory.cvefix.config.CveFixAllowedFiles;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.Bump;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CiStatusGateway;
import com.simonrowe.factory.cvefix.github.CveFixPrBodyRenderer;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import com.simonrowe.factory.cvefix.persistence.FindingSuppressor;
import com.simonrowe.factory.git.RepositoryWorkspace;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Spring-managed activity adapter wiring Dependency-Track, the fix agent, the shared git
 * workspace, and GitHub for the scheduled CVE-fix flow.
 */
@Component
@ActivityImpl(taskQueues = CveFixTaskQueues.CVE_FIX)
public class CveFixActivitiesImpl implements CveFixActivities {

  private static final String WORKSPACE_PREFIX = "cve-fix-";

  private final DependencyTrackClient dependencyTrackClient;
  private final FindingSuppressor suppressor;
  private final RepositoryWorkspaceFactory workspaceFactory;
  private final GitHubCredentials credentials;
  private final FixEngine fixEngine;
  private final CveFixPrGateway prGateway;
  private final CiStatusGateway ciStatusGateway;
  private final CveFixRunRepository runRepository;
  private final CveFixProperties properties;

  /**
   * Creates the activity adapter.
   *
   * @param dependencyTrackClient reads Dependency-Track's current findings
   * @param suppressor drops components already recorded as unfixable
   * @param workspaceFactory prepares, validates and pushes the disposable git checkout
   * @param credentials resolves the GitHub App installation id used for clone and push
   * @param fixEngine proposes dependency bumps by editing the checkout in place
   * @param prGateway finds, opens and comments on the CVE-fix pull request
   * @param ciStatusGateway reads the aggregated CI outcome for a commit
   * @param runRepository persists the outcome of each CVE-fix run
   * @param properties the CVE-fix module configuration
   */
  public CveFixActivitiesImpl(
      final DependencyTrackClient dependencyTrackClient,
      final FindingSuppressor suppressor,
      final RepositoryWorkspaceFactory workspaceFactory,
      final GitHubCredentials credentials,
      final FixEngine fixEngine,
      final CveFixPrGateway prGateway,
      final CiStatusGateway ciStatusGateway,
      final CveFixRunRepository runRepository,
      final CveFixProperties properties) {
    this.dependencyTrackClient = dependencyTrackClient;
    this.suppressor = suppressor;
    this.workspaceFactory = workspaceFactory;
    this.credentials = credentials;
    this.fixEngine = fixEngine;
    this.prGateway = prGateway;
    this.ciStatusGateway = ciStatusGateway;
    this.runRepository = runRepository;
    this.properties = properties;
  }

  @Override
  public String findOpenPrUrl() {
    return prGateway.findOpen().map(CveFixPrGateway.OpenPullRequest::htmlUrl).orElse(null);
  }

  @Override
  public List<ComponentFindings> fetchActionableFindings() {
    List<ComponentFindings> grouped = ComponentFindings.group(dependencyTrackClient.findings());
    return suppressor.retainActionable(grouped);
  }

  @Override
  public PushResult proposeAndPush(
      final List<ComponentFindings> components,
      final String failureContext,
      final List<String> rejectedBumps) {
    Consumer<String> heartbeat = detail -> Activity.getExecutionContext().heartbeat(detail);
    Long installationId = credentials.installationId(properties.owner(), properties.repository());
    try (RepositoryWorkspace workspace =
        workspaceFactory.create(
            properties.owner(), properties.repository(), installationId,
            properties.workspaceRoot(), WORKSPACE_PREFIX, heartbeat)) {
      // The clone is a fresh shallow clone of the default branch every time, so rejectedBumps is
      // the only record the agent gets of what an earlier attempt on this run already tried.
      FixProposal proposal =
          fixEngine.propose(
              workspace, components, failureContext,
              rejectedBumps == null ? List.of() : rejectedBumps, heartbeat);
      List<String> changed = workspaceFactory.changedPaths(workspace, heartbeat);
      // Validated even when nothing was proposed: an agent that reports no bumps while still
      // writing outside the allowlist must fail loudly, not return a silent, unreported success.
      RepositoryWorkspaceFactory.validateAllowedPaths(changed, CveFixAllowedFiles.ALL);
      if (proposal.isEmpty() || changed.isEmpty()) {
        return new PushResult(null, summaryOf(proposal));
      }
      String headSha =
          workspaceFactory.commitAndPush(
              workspace, properties.branch(), commitMessage(proposal),
              properties.gitAuthorName(), properties.gitAuthorEmail(), installationId, heartbeat);
      return new PushResult(headSha, summaryOf(proposal));
    }
  }

  @Override
  public CveFixPrGateway.OpenPullRequest openPullRequest(final FixSummary summary) {
    return prGateway.open(CveFixPrBodyRenderer.title(summary), CveFixPrBodyRenderer.body(summary));
  }

  @Override
  public CiOutcome checkCi(final String headSha) {
    return ciStatusGateway.outcomeFor(headSha);
  }

  @Override
  public String ciFailureLogs(final String headSha) {
    return ciStatusGateway.failureLogs(headSha);
  }

  @Override
  public void commentOnPullRequest(final int number, final String body) {
    prGateway.comment(number, body);
  }

  @Override
  public void recordUnfixable(
      final List<UnfixableComponent> unfixable, final List<ComponentFindings> components) {
    suppressor.record(unfixable, components);
  }

  @Override
  public void recordRun(final CveFixRunRecord record) {
    runRepository.save(record);
  }

  private static FixSummary summaryOf(final FixProposal proposal) {
    return new FixSummary(
        proposal.bumps().stream().map(Bump::describe).toList(), proposal.unfixable(),
        proposal.summary());
  }

  private static String commitMessage(final FixProposal proposal) {
    return "chore: bump dependencies with Dependency-Track findings\n\n"
        + String.join("\n", proposal.bumps().stream().map(Bump::describe).toList());
  }
}
