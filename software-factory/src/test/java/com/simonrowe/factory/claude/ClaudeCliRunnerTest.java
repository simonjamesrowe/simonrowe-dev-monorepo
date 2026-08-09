package com.simonrowe.factory.claude;

import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(command).containsSequence("--disallowedTools", "mcp__*");
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
}
