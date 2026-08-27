package com.simonrowe.factory.deploy.shell;

import com.simonrowe.factory.codereview.agent.ProcessRunner;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Runs one phase of {@code scripts/restart-prod.sh}.
 *
 * <p>This is the whole of the Java side's relationship with Docker: it never invokes {@code
 * docker} itself. The script is the single deploy mechanism, shared with the human who types
 * {@code ./scripts/restart-prod.sh} by hand, so there is exactly one place where the deploy
 * behaviour lives.
 *
 * <p>Only ever constructed on the {@code deployer}, because its only caller is {@code
 * DeployActivitiesImpl}, which is gated on {@code factory.deploy.enabled}.
 */
@Component
public class PhaseRunner {

  /** Exit code the script uses for "declined, with no side effects". */
  public static final int EXIT_DECLINED = 2;

  private final ProcessRunner processRunner;
  private final DeployProperties properties;

  public PhaseRunner(final ProcessRunner processRunner, final DeployProperties properties) {
    this.processRunner = processRunner;
    this.properties = properties;
  }

  /**
   * Runs a phase.
   *
   * @param phase the phase to run; must have a script argument
   * @param targetSha the commit argument for {@code sync-config} / {@code rollback-config}, else
   *     null
   * @param imageTag the tag {@code pull} should fetch, or null for the script's default
   * @param dryRun whether to pass {@code DRY_RUN=1} to the script
   * @param heartbeat called periodically while the child process runs, so a long phase does not
   *     trip the activity's heartbeat timeout
   * @return what the phase did
   */
  public PhaseExecution run(
      final DeployPhase phase,
      final String targetSha,
      final String imageTag,
      final boolean dryRun,
      final Consumer<String> heartbeat) {
    String argument = phase.argument();
    if (argument == null) {
      throw new IllegalArgumentException(phase + " has no script argument");
    }
    return execute(argument, argumentsOf(targetSha), imageTag, dryRun, heartbeat);
  }

  /**
   * Runs one of the script's read-only evidence-gathering arguments.
   *
   * <p>Takes a raw script argument rather than a {@link DeployPhase} on purpose: {@code
   * compose-ps}, {@code container-logs} and {@code commit-range} are evidence gathering, not
   * deploy phases, and putting them in that enum would make them appear in a run record's ordered
   * phase list as though they were steps of the deploy.
   *
   * <p>Routed through the script rather than shelling out to {@code docker} here so that every
   * docker invocation in the whole feature lives in one file.
   *
   * @param argument the script argument
   * @param arguments extra positional arguments
   * @return what the script printed
   */
  public PhaseExecution capture(
      final String argument, final Consumer<String> heartbeat, final String... arguments) {
    return execute(argument, List.of(arguments), null, false, heartbeat);
  }

  private static java.util.List<String> argumentsOf(final String targetSha) {
    return targetSha == null || targetSha.isBlank() ? List.of() : List.of(targetSha);
  }

  private PhaseExecution execute(
      final String argument,
      final java.util.List<String> arguments,
      final String imageTag,
      final boolean dryRun,
      final Consumer<String> heartbeat) {
    Path script = Path.of(properties.script());
    // The repo directory, not the script's own directory: `sync-config` runs git in the working
    // directory and the compose commands resolve `.env` relative to it.
    Path workingDirectory = Path.of(properties.repoDir());

    var command = new java.util.ArrayList<String>();
    command.add("bash");
    command.add(script.toString());
    command.add(argument);
    command.addAll(arguments);

    long startedAt = System.nanoTime();
    ProcessRunner.ProcessResult result =
        processRunner.run(
            command,
            workingDirectory,
            null,
            environment(imageTag, dryRun),
            // Nothing is stripped: unlike the agent, this child process is the deploy itself and
            // legitimately needs the environment it was given. ClaudeCliRunner's allowlist exists
            // because the agent reads attacker-authored branches; the script reads nothing.
            Set.of(),
            properties.phaseTimeout(),
            heartbeat);
    long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;

    // stderr is the script's human narration and stdout its machine-readable key=value lines, so
    // both are kept and stdout is parsed separately.
    String output = result.standardOutput() + result.standardError();
    return new PhaseExecution(
        result.exitCode(), output, parseKeyValues(result.standardOutput()), durationMillis);
  }

  private Map<String, String> environment(final String imageTag, final boolean dryRun) {
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put("COMPOSE_FILE", properties.composeFile());
    environment.put("SERVICES", String.join(" ", properties.services()));
    environment.put("STATE_DIR", properties.stateDir());
    environment.put("REPO_URL", properties.repoUrl());
    environment.put("RECREATABLE", String.join(" ", properties.recreatable()));
    if (imageTag != null && !imageTag.isBlank()) {
      environment.put("IMAGE_TAG", imageTag);
    }
    if (dryRun) {
      environment.put("DRY_RUN", "1");
    }
    return environment;
  }

  /**
   * Reads the script's {@code key=value} contract lines from stdout.
   *
   * <p>Lines that are not {@code key=value} are ignored rather than rejected, because stdout also
   * carries ordinary progress output and a phase must not fail on the shape of its own logging.
   */
  private static Map<String, String> parseKeyValues(final String standardOutput) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : standardOutput.split("\n")) {
      String trimmed = line.strip();
      int separator = trimmed.indexOf('=');
      if (separator <= 0 || trimmed.contains(" ") && trimmed.indexOf(' ') < separator) {
        continue;
      }
      values.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
    }
    return values;
  }

  /**
   * One phase's result.
   *
   * @param exitCode the script's exit code
   * @param output stdout and stderr, concatenated
   * @param values the {@code key=value} lines parsed from stdout
   * @param durationMillis how long the process ran
   */
  public record PhaseExecution(
      int exitCode, String output, Map<String, String> values, long durationMillis) {

    /** Whether the phase succeeded. */
    public boolean succeeded() {
      return exitCode == 0;
    }

    /** Whether the phase declined without side effects, rather than failing. */
    public boolean declined() {
      return exitCode == EXIT_DECLINED;
    }

    /**
     * A {@code key=value} value from the script's stdout.
     *
     * @param key the key
     * @return the value, or null when the script did not emit it
     */
    public String value(final String key) {
      return values.get(key);
    }
  }
}
