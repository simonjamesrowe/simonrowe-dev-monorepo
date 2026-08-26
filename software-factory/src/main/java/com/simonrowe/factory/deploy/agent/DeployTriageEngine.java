package com.simonrowe.factory.deploy.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.workflow.DeployActivities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Diagnoses a failed deploy with a headless Claude run.
 *
 * <p>The agent is given <strong>{@code Read} only, scoped to the evidence directory, and no
 * {@code Bash} tool</strong>. It is handed captured output and asked to explain it, exactly as the
 * CVE-fix flow hands over Dependency-Track findings — it never touches Docker, git or a
 * credential, and it cannot investigate beyond what was captured for it. That is deliberate:
 * this code runs in the one container on the box holding {@code /var/run/docker.sock}, and the
 * agent must not be able to reach it.
 *
 * <p>It also holds no credential by construction: {@link ClaudeCliRunner} strips every environment
 * variable outside its own allowlist before starting the child process, so the GitHub App key
 * path, the Dependency-Track key and the webhook secret are all absent from the agent's
 * environment without this class having to know they exist. Do not "helpfully" pass any of them
 * through.
 */
@Component
public class DeployTriageEngine implements TriageEngine {

  private final DeployProperties properties;
  private final ClaudeCliRunner runner;
  private final String schema;

  /**
   * Creates the engine, loading the structured-output schema from the classpath once.
   *
   * @param properties the deploy configuration, including the agent's process settings
   * @param runner the shared headless Claude launcher
   */
  public DeployTriageEngine(final DeployProperties properties, final ClaudeCliRunner runner) {
    this.properties = properties;
    this.runner = runner;
    this.schema = loadSchema();
  }

  @Override
  public DeployActivities.Triage diagnose(
      final Path evidenceDirectory, final Consumer<String> heartbeat) {
    heartbeat.accept("Diagnosing the deploy failure");
    JsonNode structured =
        runner.runStructured(
            new ClaudeCliRunner.Invocation(
                properties.agent().command(),
                properties.agent().model(),
                properties.agent().effort(),
                properties.agent().maxTurns(),
                properties.agent().timeout(),
                tools(),
                allowedTools(),
                schema,
                prompt(),
                evidenceDirectory),
            heartbeat);
    return parse(structured);
  }

  /**
   * The tool surface. Read-only, and no {@code Bash}.
   *
   * <p>{@code Glob} and {@code Grep} are included so the agent can find its way around the
   * evidence directory and search a long log rather than reading all of it into context. Neither
   * can write, and both are confined by {@link #allowedTools()} to the evidence directory.
   */
  static List<String> tools() {
    return List.of("Read", "Glob", "Grep");
  }

  /**
   * Read anywhere under the working directory — which is the evidence directory and nothing else.
   */
  static List<String> allowedTools() {
    return new ArrayList<>(List.of("Read(./**)", "Glob", "Grep"));
  }

  private static String prompt() {
    return """
        A production deploy of simonrowe.dev failed and has been rolled back automatically.
        Your job is to explain what broke, for the maintainer to read later.

        The current directory holds everything that was captured at the moment of failure:

          phase-output.txt    stdout and stderr of the deploy phase that failed
          compose-ps.txt      `docker compose ps -a` at the moment of failure
          container-logs.txt  recent logs of the containers that were not healthy
          commit-range.txt    the commits between the previously deployed version and this one

        Read them and produce a diagnosis.

        Rules, in order of importance:

        1. Rest every claim on something in those files, and quote the line you are resting on.
        2. If the evidence does not identify a cause, say so and set confidence to "low". That is
           a useful answer. A confident-sounding guess is worse than "the logs do not say",
           because it sends the maintainer down the wrong path.
        3. You have no shell and no access to the running system. Do not propose that you checked
           anything; you are reading a snapshot.
        4. `suggestedNextStep` must be one concrete action. Assume the site may still be showing a
           maintenance page, and assume no credential beyond what an operator on the host already
           has.
        5. Consider the deploy machinery itself as a candidate cause. A change to
           `scripts/restart-prod.sh`, `docker-compose.prod.yml` or the nginx configuration in the
           commit range can break a deploy in a way that looks like an application fault — that is
           what the "deploy-script" and "configuration" categories are for.
        """;
  }

  private DeployActivities.Triage parse(final JsonNode structured) {
    List<String> failingServices = new ArrayList<>();
    for (JsonNode service : structured.path("failingServices")) {
      failingServices.add(service.asText());
    }
    List<String> suspectCommits = new ArrayList<>();
    for (JsonNode commit : structured.path("suspectCommits")) {
      String sha = commit.path("sha").asText("");
      String why = commit.path("why").asText("");
      suspectCommits.add(sha.isBlank() ? why : sha + " — " + why);
    }
    return new DeployActivities.Triage(
        structured.path("headline").asText("Deploy failed"),
        structured.path("diagnosis").asText(""),
        structured.path("confidence").asText("low"),
        structured.path("suspectedCause").asText("unknown"),
        failingServices,
        suspectCommits,
        structured.path("suggestedNextStep").asText(""));
  }

  private static String loadSchema() {
    try (InputStream stream =
        DeployTriageEngine.class.getResourceAsStream("/deploy-triage-schema.json")) {
      if (stream == null) {
        throw new IllegalStateException("deploy-triage-schema.json is missing from the classpath");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read deploy-triage-schema.json", exception);
    }
  }
}
