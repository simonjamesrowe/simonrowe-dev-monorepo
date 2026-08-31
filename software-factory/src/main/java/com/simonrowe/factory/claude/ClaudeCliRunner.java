package com.simonrowe.factory.claude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Shared headless Claude Code launcher. Owns the argv shape, the child-process environment
 * allowlist, and structured-output parsing so every module launches the agent the same way.
 */
@Component
public class ClaudeCliRunner {

  /** Fields of the CLI result envelope that say why a run stopped. */
  private static final List<String> RESULT_DETAIL_FIELDS =
      List.of("subtype", "terminal_reason", "api_error_status");

  /**
   * Credentials Claude itself needs. Everything outside this set and {@link #PROCESS_ENVIRONMENT}
   * is stripped before the agent runs, because the agent reads attacker-authored pull request
   * branches.
   */
  private static final Set<String> SAFE_SECRET_ENVIRONMENT =
      Set.of(
          "ANTHROPIC_API_KEY",
          "CLAUDE_CODE_OAUTH_TOKEN",
          "ANTHROPIC_BASE_URL",
          "AWS_ACCESS_KEY_ID",
          "AWS_SECRET_ACCESS_KEY",
          "AWS_SESSION_TOKEN",
          "AWS_REGION",
          "AWS_DEFAULT_REGION",
          "GOOGLE_APPLICATION_CREDENTIALS");

  /** Non-secret variables a child process needs to run at all. */
  private static final Set<String> PROCESS_ENVIRONMENT =
      Set.of(
          "PATH",
          "HOME",
          "USER",
          "LOGNAME",
          "SHELL",
          "PWD",
          "TMPDIR",
          "TZ",
          "TERM",
          "LANG",
          "LC_ALL",
          "LC_CTYPE",
          "XDG_CONFIG_HOME",
          "XDG_CACHE_HOME",
          "XDG_DATA_HOME",
          "XDG_RUNTIME_DIR",
          "HTTP_PROXY",
          "HTTPS_PROXY",
          "NO_PROXY",
          "http_proxy",
          "https_proxy",
          "no_proxy");

  private final ProcessRunner processRunner;
  private final ObjectMapper objectMapper;

  public ClaudeCliRunner(final ProcessRunner processRunner, final ObjectMapper objectMapper) {
    this.processRunner = processRunner;
    this.objectMapper = objectMapper;
  }

  /** Runs claude -p headlessly and returns the parsed structured_output node. */
  public JsonNode runStructured(final Invocation invocation, final Consumer<String> heartbeat) {
    ProcessRunner.ProcessResult process =
        processRunner.run(
            command(invocation),
            invocation.workingDirectory(),
            invocation.prompt(),
            Map.of("CLAUDE_CODE_SKIP_PROMPT_HISTORY", "1"),
            sensitiveEnvironmentVariables(System.getenv().keySet()),
            invocation.timeout(),
            heartbeat);
    if (process.exitCode() != 0) {
      throw new IllegalStateException(
          "Claude exited with " + process.exitCode() + ": " + failureDetail(process));
    }
    try {
      JsonNode root = objectMapper.readTree(process.standardOutput());
      JsonNode structured = root.path("structured_output");
      if (structured.isMissingNode() || structured.isNull()) {
        throw new IllegalStateException("Claude returned no structured_output");
      }
      return structured;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to parse Claude structured output", exception);
    }
  }

  static List<String> command(final Invocation invocation) {
    List<String> command = new ArrayList<>();
    command.add(invocation.command());
    command.add("-p");
    command.add("--safe-mode");
    command.add("--strict-mcp-config");
    command.add("--tools");
    command.add(String.join(",", invocation.tools()));
    command.add("--allowedTools");
    command.addAll(invocation.allowedTools());
    command.add("--disallowedTools");
    command.add("mcp__*");
    // Every invocation grants Read/Edit/Write against "./**", which technically includes
    // .git/ (git status never reports changes inside its own metadata directory, so
    // RepositoryWorkspaceFactory's allowlist check can't see a write there). Deny it here, once,
    // for every caller rather than per-engine, mirroring the --allowedTools "Tool(glob)" syntax.
    command.add("Edit(./.git/**)");
    command.add("Write(./.git/**)");
    command.add("--permission-mode");
    command.add("dontAsk");
    command.add("--no-session-persistence");
    command.add("--output-format");
    command.add("json");
    command.add("--json-schema");
    command.add(invocation.schemaJson());
    command.add("--model");
    command.add(invocation.model());
    command.add("--effort");
    command.add(invocation.effort());
    command.add("--max-turns");
    command.add(Integer.toString(invocation.maxTurns()));
    return command;
  }

  /**
   * Returns the variables to strip: everything the agent has no reason to see. This is an
   * allowlist rather than a blocklist of suspicious names, because patterns miss real secrets —
   * {@code DEPENDENCYTRACK_KEK}, {@code REDIS_AUTH} and {@code SALT} all match none of
   * TOKEN/SECRET/PASSWORD/_KEY. Anything genuinely needed must be added to {@link
   * #SAFE_SECRET_ENVIRONMENT} or {@link #PROCESS_ENVIRONMENT}, never to the worker environment
   * alone.
   */
  static Set<String> sensitiveEnvironmentVariables(final Set<String> names) {
    Set<String> removed = new HashSet<>();
    for (String name : names) {
      if (!SAFE_SECRET_ENVIRONMENT.contains(name) && !PROCESS_ENVIRONMENT.contains(name)) {
        removed.add(name);
      }
    }
    return removed;
  }

  /**
   * Describes a non-zero exit. In {@code -p --output-format json} mode the CLI reports why it
   * stopped as JSON on stdout and leaves stderr empty, so a stderr-only message is blank for every
   * agent-side failure — exhausted turns, API errors, an unusable model. Read stdout first.
   */
  String failureDetail(final ProcessRunner.ProcessResult process) {
    String stderr = sanitize(process.standardError(), 400);
    String stdout = agentResultDetail(process.standardOutput());
    if (stderr.isEmpty()) {
      return stdout;
    }
    return stdout.isEmpty() ? "stderr: " + stderr : stdout + " | stderr: " + stderr;
  }

  private String agentResultDetail(final String standardOutput) {
    JsonNode root;
    try {
      root = objectMapper.readTree(standardOutput);
    } catch (IOException exception) {
      return sanitize(standardOutput, 800);
    }
    List<String> detail = new ArrayList<>();
    for (String field : RESULT_DETAIL_FIELDS) {
      JsonNode value = root.path(field);
      if (value.isValueNode() && !value.isNull() && !value.asText().isBlank()) {
        detail.add(field + "=" + value.asText());
      }
    }
    for (JsonNode error : root.path("errors")) {
      detail.add("error=" + error.asText());
    }
    return detail.isEmpty()
        ? sanitize(standardOutput, 800)
        : sanitize(String.join(", ", detail), 800);
  }

  private static String sanitize(final String input, final int maximumLength) {
    if (input == null) {
      return "";
    }
    String collapsed = input.replace('\u0000', ' ').replaceAll("[\\r\\n]+", " ").trim();
    return abbreviate(collapsed, maximumLength);
  }

  private static String abbreviate(final String value, final int maximumLength) {
    return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
  }

  /** One headless run: model, limits, tool surface, schema, and prompt. */
  public record Invocation(
      String command, String model, String effort, int maxTurns, Duration timeout,
      List<String> tools, List<String> allowedTools, String schemaJson, String prompt,
      Path workingDirectory) {
  }
}
