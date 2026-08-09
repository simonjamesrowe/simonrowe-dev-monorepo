package com.simonrowe.factory.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.agent.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaudeCliRunnerTest {

  private static ClaudeCliRunner.Invocation invocation() {
    return new ClaudeCliRunner.Invocation(
        "/usr/local/bin/claude",
        "haiku",
        "low",
        8,
        Duration.ofMinutes(5),
        List.of("Read", "Glob", "Grep"),
        List.of("Read(./**)", "Glob", "Grep"),
        "{\"type\":\"object\"}",
        "prompt",
        Path.of("/tmp"));
  }

  private static ClaudeCliRunner runner() {
    return new ClaudeCliRunner(mock(ProcessRunner.class), new ObjectMapper());
  }

  @Test
  void buildsHeadlessCommandWithModelEffortAndSchema() {
    List<String> command = ClaudeCliRunner.command(invocation());

    assertThat(command).startsWith("/usr/local/bin/claude", "-p", "--safe-mode");
    assertThat(command).containsSequence("--tools", "Read,Glob,Grep");
    assertThat(command).containsSequence("--model", "haiku");
    assertThat(command).containsSequence("--effort", "low");
    assertThat(command).containsSequence("--max-turns", "8");
    assertThat(command).containsSequence("--json-schema", "{\"type\":\"object\"}");
    assertThat(command).containsSequence("--permission-mode", "dontAsk");
    assertThat(command).contains("--no-session-persistence");
    assertThat(command)
        .containsSequence(
            "--disallowedTools", "mcp__*", "Edit(./.git/**)", "Write(./.git/**)");
  }

  @Test
  void reportsWhyTheAgentStoppedWhenStderrIsEmpty() {
    String output =
        """
        {
          "type": "result",
          "is_error": true,
          "subtype": "error_max_turns",
          "terminal_reason": "max_turns",
          "errors": ["Reached maximum number of turns (12)"]
        }
        """;

    String detail = runner().failureDetail(new ProcessRunner.ProcessResult(1, output, ""));

    assertThat(detail)
        .contains("subtype=error_max_turns")
        .contains("terminal_reason=max_turns")
        .contains("error=Reached maximum number of turns (12)");
  }

  @Test
  void fallsBackToRawStreamsWhenStdoutIsNotTheResultEnvelope() {
    String detail =
        runner().failureDetail(new ProcessRunner.ProcessResult(1, "not json", "unknown option"));

    assertThat(detail).contains("not json").contains("stderr: unknown option");
  }

  @Test
  void stripsEverythingOutsideTheAllowlistFromTheChildEnvironment() {
    Set<String> removed =
        ClaudeCliRunner.sensitiveEnvironmentVariables(
            Set.of("PATH", "HOME", "CLAUDE_CODE_OAUTH_TOKEN", "GITHUB_WEBHOOK_SECRET",
                "FACTORY_TRIGGER_TOKEN", "DEPENDENCYTRACK_KEK"));

    assertThat(removed)
        .containsExactlyInAnyOrder(
            "GITHUB_WEBHOOK_SECRET", "FACTORY_TRIGGER_TOKEN", "DEPENDENCYTRACK_KEK");
  }

  @Test
  void keepsClaudeCredentialsButStripsUnrelatedSecrets() {
    var removed =
        ClaudeCliRunner.sensitiveEnvironmentVariables(
            Set.of(
                "CLAUDE_CODE_OAUTH_TOKEN",
                "ANTHROPIC_API_KEY",
                "GITHUB_WEBHOOK_SECRET",
                "FACTORY_TRIGGER_TOKEN",
                "REVIEWER_TRIGGER_TOKEN",
                "TEMPORAL_DB_PASSWORD",
                "PATH"));

    assertThat(removed)
        .containsExactlyInAnyOrder(
            "GITHUB_WEBHOOK_SECRET",
            "FACTORY_TRIGGER_TOKEN",
            "REVIEWER_TRIGGER_TOKEN",
            "TEMPORAL_DB_PASSWORD");
  }

  @Test
  void stripsSecretsWhoseNamesLookHarmless() {
    var removed =
        ClaudeCliRunner.sensitiveEnvironmentVariables(
            Set.of("DEPENDENCYTRACK_KEK", "REDIS_AUTH", "SALT", "MINIO_ROOT_USER"));

    assertThat(removed)
        .containsExactlyInAnyOrder("DEPENDENCYTRACK_KEK", "REDIS_AUTH", "SALT", "MINIO_ROOT_USER");
  }

  @Test
  void keepsTheProcessEnvironmentTheAgentNeedsToRun() {
    var removed =
        ClaudeCliRunner.sensitiveEnvironmentVariables(
            Set.of("PATH", "HOME", "LANG", "TMPDIR", "HTTPS_PROXY"));

    assertThat(removed).isEmpty();
  }

  @Test
  void stripsUnrecognisedVariablesByDefault() {
    var removed =
        ClaudeCliRunner.sensitiveEnvironmentVariables(
            Set.of("SOME_FUTURE_PROD_SETTING", "LANGFUSE_ENVIRONMENT"));

    assertThat(removed)
        .containsExactlyInAnyOrder("SOME_FUTURE_PROD_SETTING", "LANGFUSE_ENVIRONMENT");
  }
}
