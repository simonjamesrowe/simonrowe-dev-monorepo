package com.simonrowe.factory.ticketagent.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the ticket agent.
 *
 * @param enabled registers the activities and the intake schedule; true on software-factory only.
 *     The schedule is created paused, so switching this on starts nothing by itself
 * @param owner the repository owner the agent works in
 * @param repository the repository name; one repository, deliberately
 * @param label the label a person adds to hand a ticket over
 * @param stuckLabel the label added when the agent hands a ticket back after failing
 * @param branchPrefix every agent branch starts with this, which is how a webhook finds its run
 * @param maxAttempts agent runs per ticket, the first one included
 * @param maxInFlight tickets in {@code In Progress} at once; parked runs waiting for a merge do
 *     not count
 * @param ciWorkflowName the GitHub Actions workflow whose completion is the build result
 * @param checksWait how long one attempt waits for CI before reading GitHub directly
 * @param reviewWait how long to keep looking for the {@code Code Review} check once CI is done
 * @param mergeWait how long a green pull request is watched for its merge
 * @param inProgressState the Linear state a claimed ticket moves to
 * @param inReviewState the Linear state a ticket with a green pull request moves to
 * @param returnState the Linear state a handed-back ticket moves to
 * @param workspaceRoot where each attempt's checkout is created
 * @param gitAuthorName the commit author
 * @param gitAuthorEmail the commit author's address
 * @param logLines how many lines of a failed job's log reach the repair brief
 * @param deniedPaths globs the agent may never push, whatever the ticket says
 * @param agent the Claude Code invocation
 */
@ConfigurationProperties("factory.ticket-agent")
public record TicketAgentProperties(
    boolean enabled,
    String owner,
    String repository,
    String label,
    String stuckLabel,
    String branchPrefix,
    int maxAttempts,
    int maxInFlight,
    String ciWorkflowName,
    Duration checksWait,
    Duration reviewWait,
    Duration mergeWait,
    String inProgressState,
    String inReviewState,
    String returnState,
    Path workspaceRoot,
    String gitAuthorName,
    String gitAuthorEmail,
    int logLines,
    List<String> deniedPaths,
    Agent agent) {

  /**
   * What the agent may never push, checked in Java before the push.
   *
   * <p>This overlaps {@code MergeDisposition} on purpose. That one decides what may <em>merge</em>
   * unattended; this one decides what may be <em>pushed at all</em>. A change under
   * {@code config/nginx/} is how the site goes down, and the agent should not be able to put one in
   * front of a reviewer who might wave it through.
   */
  public static final List<String> DEFAULT_DENIED_PATHS =
      List.of(
          ".env*",
          "**/.env*",
          "docker-compose*.yml",
          "docker-compose*.yaml",
          "config/**",
          "scripts/**",
          ".github/**",
          "gradlew",
          "gradlew.bat",
          "gradle/**",
          "build.gradle.kts",
          "**/build.gradle.kts",
          "settings.gradle.kts",
          "Dockerfile*",
          "**/Dockerfile*",
          "frontend/package.json",
          "frontend/package-lock.json");

  public TicketAgentProperties {
    owner = blankTo(owner, "simonjamesrowe");
    repository = blankTo(repository, "simonrowe-dev-monorepo");
    label = blankTo(label, "factory:agent");
    stuckLabel = blankTo(stuckLabel, "agent:stuck");
    branchPrefix = blankTo(branchPrefix, "agent/");
    maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
    maxInFlight = maxInFlight <= 0 ? 1 : maxInFlight;
    ciWorkflowName = blankTo(ciWorkflowName, "CI");
    checksWait = checksWait == null ? Duration.ofHours(2) : checksWait;
    reviewWait = reviewWait == null ? Duration.ofMinutes(40) : reviewWait;
    mergeWait = mergeWait == null ? Duration.ofDays(14) : mergeWait;
    inProgressState = blankTo(inProgressState, "In Progress");
    inReviewState = blankTo(inReviewState, "In Review");
    returnState = blankTo(returnState, "Todo");
    workspaceRoot =
        workspaceRoot == null
            ? Path.of(System.getProperty("java.io.tmpdir"), "software-factory")
            : workspaceRoot;
    gitAuthorName = blankTo(gitAuthorName, "simonrowe-software-factory[bot]");
    gitAuthorEmail =
        blankTo(gitAuthorEmail, "simonrowe-software-factory[bot]@users.noreply.github.com");
    logLines = logLines <= 0 ? 200 : logLines;
    deniedPaths =
        deniedPaths == null || deniedPaths.isEmpty()
            ? DEFAULT_DENIED_PATHS
            : List.copyOf(deniedPaths);
    agent = agent == null ? new Agent(null, null, null, 0, null) : agent;
  }

  /** {@code owner/repository}. */
  public String slug() {
    return owner + "/" + repository;
  }

  private static String blankTo(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * The Claude Code invocation for one attempt.
   *
   * @param command the binary
   * @param model the model alias
   * @param effort the reasoning effort
   * @param maxTurns the turn budget for one attempt
   * @param timeout how long one attempt may run
   */
  public record Agent(String command, String model, String effort, int maxTurns, Duration timeout) {

    public Agent {
      command = command == null || command.isBlank() ? "claude" : command;
      model = model == null || model.isBlank() ? "sonnet" : model;
      effort = effort == null || effort.isBlank() ? "medium" : effort;
      maxTurns = maxTurns <= 0 ? 60 : maxTurns;
      timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
    }
  }
}
