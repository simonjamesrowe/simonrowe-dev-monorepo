package com.simonrowe.factory.cvefix.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the scheduled CVE-fix flow.
 *
 * <p>There is deliberately no installation id here: the activity resolves it from
 * {@code GitHubCredentials.installationId(owner, repository)} at run time, so there is no
 * configured value that can be left null and silently degrade every git operation to anonymous.
 */
@ConfigurationProperties("factory.cvefix")
public record CveFixProperties(
    boolean enabled,
    String owner,
    String repository,
    String branch,
    String baseBranch,
    String gitAuthorName,
    String gitAuthorEmail,
    Path workspaceRoot,
    DependencyTrack dependencyTrack,
    Agent agent,
    Ci ci) {

  public CveFixProperties {
    owner = owner == null ? "simonjamesrowe" : owner;
    repository = repository == null ? "simonrowe-dev-monorepo" : repository;
    branch = branch == null ? "chore/dependency-cve-fixes" : branch;
    baseBranch = baseBranch == null ? "main" : baseBranch;
    gitAuthorName = gitAuthorName == null ? "simonrowe-code-reviewer[bot]" : gitAuthorName;
    gitAuthorEmail =
        gitAuthorEmail == null
            ? "simonrowe-code-reviewer[bot]@users.noreply.github.com"
            : gitAuthorEmail;
    workspaceRoot =
        workspaceRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : workspaceRoot;
    dependencyTrack = dependencyTrack == null ? DependencyTrack.defaults() : dependencyTrack;
    agent = agent == null ? Agent.defaults() : agent;
    ci = ci == null ? Ci.defaults() : ci;
  }

  /** Dependency-Track endpoint, credential and the projects in scope. */
  public record DependencyTrack(
      String baseUrl, String apiKey, List<String> projects, Duration requestTimeout) {

    public DependencyTrack {
      baseUrl = baseUrl == null ? "http://dependencytrack-apiserver:8080" : baseUrl;
      apiKey = apiKey == null ? "" : apiKey;
      projects =
          projects == null || projects.isEmpty()
              ? List.of("simonrowe-dev/backend", "simonrowe-dev/frontend")
              : List.copyOf(projects);
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    static DependencyTrack defaults() {
      return new DependencyTrack(null, null, null, null);
    }
  }

  /** Claude CLI process configuration for the fix phase. */
  public record Agent(
      String command, String model, String effort, int maxTurns, Duration timeout) {

    public Agent {
      command = command == null ? "claude" : command;
      model = model == null ? "sonnet" : model;
      effort = effort == null ? "medium" : effort;
      maxTurns = maxTurns == 0 ? 40 : maxTurns;
      timeout = timeout == null ? Duration.ofMinutes(15) : timeout;
    }

    static Agent defaults() {
      return new Agent(null, null, null, 0, null);
    }
  }

  /**
   * CI polling. {@code repairBudget} bounds how many times the agent may react to a red build
   * before the run gives up and leaves the pull request open for a human. {@code advisoryChecks}
   * names check runs whose conclusion must never make the build RED.
   */
  public record Ci(
      Duration pollInterval, int repairBudget, Duration maxWait, List<String> advisoryChecks) {

    public Ci {
      // 3 minutes keeps unauthenticated GitHub API use at ~20 requests/hour typical, and
      // ~40/hour worst case while polling a red pull request (reading the outcome and then its
      // failure logs are two separate requests) — both inside the 60/hour per-IP limit that
      // route is subject to. See CiStatusGateway.
      pollInterval = pollInterval == null ? Duration.ofMinutes(3) : pollInterval;
      repairBudget = repairBudget == 0 ? 3 : repairBudget;
      // 3h, not 45m: one repair iteration costs up to agent.timeout (15m) plus a whole CI
      // cycle, so repairBudget + 1 iterations do not fit in 45 minutes and the wall-clock cap
      // would silently truncate the documented budget.
      maxWait = maxWait == null ? Duration.ofHours(3) : maxWait;
      // The promptfoo evals job is continue-on-error advisory. Job-level continue-on-error
      // keeps the *run* from failing; it is not documented to rewrite the check-run
      // conclusion, so this list — not that setting — is what guarantees an advisory job
      // cannot burn the repair budget. See CiStatusGateway and Task 8 Step 4.
      advisoryChecks =
          advisoryChecks == null || advisoryChecks.isEmpty()
              ? List.of("evaluate")
              : List.copyOf(advisoryChecks);
    }

    static Ci defaults() {
      return new Ci(null, 0, null, null);
    }
  }
}
