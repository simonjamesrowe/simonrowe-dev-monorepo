package com.simonrowe.factory.deploy.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for auto-deploy on merge.
 *
 * <p>{@code enabled} and {@code triggerEnabled} are two fields on one record deliberately. They
 * must be switchable independently — a broken deployer has to be silenceable without also
 * silencing code review, and the executor has to be enabled and rehearsed before the trigger is
 * — but they are always read together when reasoning about whether a deploy can happen at all,
 * so splitting them across two records would hide the pair.
 *
 * <p>{@code enabled} is the load-bearing one: it gates {@link
 * com.simonrowe.factory.deploy.workflow.DeployActivitiesImpl}, and the absence of that bean is
 * the only thing that keeps the Docker socket confined to the {@code deployer} container.
 *
 * <p>Every field is defaulted here rather than relied upon from {@code application.yml}, matching
 * {@code CveFixProperties}, so a partially-configured deployment boots with sane values instead
 * of nulls.
 */
@ConfigurationProperties("factory.deploy")
public record DeployProperties(
    boolean enabled,
    boolean triggerEnabled,
    String owner,
    String repository,
    String workflowName,
    String branch,
    String composeFile,
    String script,
    String repoDir,
    String repoUrl,
    List<String> services,
    List<String> recreatable,
    // Boxed, unlike the two flags above, because these two default ON and a primitive boolean
    // cannot express that: its zero value is false, so an unconfigured deployment would silently
    // lose rollback and config sync — the two behaviours that make the rest of this safe. Boxing
    // lets "absent" and "explicitly false" be told apart in the compact constructor.
    Boolean rollbackEnabled,
    Boolean syncConfig,
    String stateDir,
    Duration phaseTimeout,
    Agent agent,
    // Last in the record on purpose: appended rather than inserted, so adding it did
    // not force an edit into the middle of every positional call site in the tests.
    boolean logWatchTriggerEnabled) {

  /**
   * The services the automation may recreate.
   *
   * <p>An allowlist, not a denylist, because that is the fail-safe direction: a service added to
   * the compose file next year is held for a human by default rather than silently recreated by
   * the first deploy that touches it.
   *
   * <p>The absentees are absent for concrete reasons. Recreating {@code
   * dependencytrack-apiserver} crash-loops Dependency-Track unless its KEK comes from env;
   * {@code langfuse-clickhouse} carries a pinned image and a lazy-materialisation workaround;
   * recreating {@code pinggy} can collide with its own still-live tunnel, since one
   * {@code PINGGY_TOKEN} allows exactly one; and {@code mongodb}, {@code elasticsearch} and
   * {@code kafka} are the data.
   *
   * <p>{@code deployer} is absent because it excludes itself — recreating the container that is
   * mid-orchestration is how the backend's old redeploy path went wrong.
   */
  private static final List<String> DEFAULT_RECREATABLE =
      List.of(
          "backend",
          "frontend",
          "software-factory",
          "nginx",
          "alloy",
          "searxng",
          "temporal-ui",
          "dependencytrack-frontend");

  /** The three images CI publishes on every merge to main. */
  private static final List<String> DEFAULT_SERVICES =
      List.of("backend", "frontend", "software-factory");

  public DeployProperties {
    owner = orDefault(owner, "simonjamesrowe");
    repository = orDefault(repository, "simonrowe-dev-monorepo");
    workflowName = orDefault(workflowName, "Publish");
    branch = orDefault(branch, "main");
    composeFile = orDefault(composeFile, "/workspace/repo/docker-compose.prod.yml");
    script = orDefault(script, "/workspace/repo/scripts/restart-prod.sh");
    repoDir = orDefault(repoDir, "/workspace/repo");
    // Pinned in configuration rather than read from the checkout's own remote, so a tampered
    // remote on the host cannot redirect the fetch that sync-config validates against.
    repoUrl =
        orDefault(repoUrl, "https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git");
    stateDir = orDefault(stateDir, "/var/run/deploy-state");
    services = orDefault(services, DEFAULT_SERVICES);
    recreatable = orDefault(recreatable, DEFAULT_RECREATABLE);
    // Default ON, which is why these two are boxed: a primitive boolean's zero value is false,
    // so an unconfigured deployment would silently lose rollback and config sync.
    rollbackEnabled = rollbackEnabled == null ? Boolean.TRUE : rollbackEnabled;
    syncConfig = syncConfig == null ? Boolean.TRUE : syncConfig;
    // 30m covers the slowest single phase by a wide margin: `pull` fetches three ARM images to a
    // Raspberry Pi and `verify` allows the script's own 420s settle budget. The activity's
    // start-to-close timeout in DeployWorkflowImpl must stay above this.
    phaseTimeout = phaseTimeout == null ? Duration.ofMinutes(30) : phaseTimeout;
    agent = agent == null ? Agent.defaults() : agent;
  }

  /** Treats a blank value as absent — an empty environment variable is not a configured one. */
  private static String orDefault(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Treats an empty list as absent, and copies a supplied one so the record stays immutable. */
  private static List<String> orDefault(final List<String> value, final List<String> fallback) {
    return value == null || value.isEmpty() ? fallback : List.copyOf(value);
  }

  /** Whether {@code service} is one the automation may recreate. */
  public boolean mayRecreate(final String service) {
    return recreatable.contains(service);
  }

  /** {@code owner/repository}, the form GitHub webhook payloads are compared against. */
  public String slug() {
    return owner + "/" + repository;
  }

  /**
   * Claude CLI process configuration for the failure-triage phase.
   *
   * <p>Modest limits on purpose: the agent reads a handful of captured text files and writes a
   * diagnosis. It has no {@code Bash} tool and nothing to explore, so a large turn budget would
   * buy nothing but cost.
   */
  public record Agent(String command, String model, String effort, int maxTurns, Duration timeout) {

    public Agent {
      command = command == null || command.isBlank() ? "claude" : command;
      model = model == null || model.isBlank() ? "sonnet" : model;
      effort = effort == null || effort.isBlank() ? "medium" : effort;
      maxTurns = maxTurns == 0 ? 12 : maxTurns;
      timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
    }

    static Agent defaults() {
      return new Agent(null, null, null, 0, null);
    }
  }
}
