package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaudeCliReviewEngineTest {

  @Test
  void validatesChangedPathsDeduplicatesAndNormalizesVerdict() {
    ClaudeCliReviewEngine engine =
        new ClaudeCliReviewEngine(
            properties(),
            mock(GitWorkspaceFactory.class),
            mock(ProcessRunner.class),
            new ObjectMapper());
    String output =
        """
        {
          "type": "result",
          "subtype": "success",
          "structured_output": {
            "summary": "Found a reachable defect.",
            "verdict": "approve",
            "findings": [
              {
                "severity": "warning",
                "file": "src/App.java",
                "line": 12,
                "title": "Null result is dereferenced",
                "explanation": "A new null branch reaches this line.",
                "recommendation": "Return before dereferencing."
              },
              {
                "severity": "warning",
                "file": "src/App.java",
                "line": 12,
                "title": "Null result is dereferenced",
                "explanation": "Duplicate.",
                "recommendation": "Duplicate."
              },
              {
                "severity": "critical",
                "file": "../outside.txt",
                "line": 1,
                "title": "Outside checkout",
                "explanation": "Must be discarded.",
                "recommendation": "Discard it."
              }
            ]
          }
        }
        """;

    var report = engine.parseReviewOutput(output, List.of("src/App.java"));

    assertThat(report.verdict()).isEqualTo(Verdict.COMMENT);
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().getFirst().severity()).isEqualTo(Severity.WARNING);
    assertThat(report.findings().getFirst().file()).isEqualTo("src/App.java");
  }

  @Test
  void keepsClaudeCredentialsButStripsUnrelatedSecrets() {
    var removed =
        ClaudeCliReviewEngine.sensitiveEnvironmentVariables(
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
        ClaudeCliReviewEngine.sensitiveEnvironmentVariables(
            Set.of("DEPENDENCYTRACK_KEK", "REDIS_AUTH", "SALT", "MINIO_ROOT_USER"));

    assertThat(removed)
        .containsExactlyInAnyOrder("DEPENDENCYTRACK_KEK", "REDIS_AUTH", "SALT", "MINIO_ROOT_USER");
  }

  @Test
  void keepsTheProcessEnvironmentTheAgentNeedsToRun() {
    var removed =
        ClaudeCliReviewEngine.sensitiveEnvironmentVariables(
            Set.of("PATH", "HOME", "LANG", "TMPDIR", "HTTPS_PROXY"));

    assertThat(removed).isEmpty();
  }

  @Test
  void stripsUnrecognisedVariablesByDefault() {
    var removed =
        ClaudeCliReviewEngine.sensitiveEnvironmentVariables(
            Set.of("SOME_FUTURE_PROD_SETTING", "LANGFUSE_ENVIRONMENT"));

    assertThat(removed)
        .containsExactlyInAnyOrder("SOME_FUTURE_PROD_SETTING", "LANGFUSE_ENVIRONMENT");
  }

  private static CodeReviewProperties properties() {
    return new CodeReviewProperties(
        new CodeReviewProperties.Github(
            "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
        new CodeReviewProperties.Agent(
            "claude",
            "sonnet",
            "medium",
            12,
            Duration.ofMinutes(15),
            Path.of("/tmp/reviewer-test"),
            2_097_152,
            80,
            "v1"),
        new CodeReviewProperties.Api(""));
  }
}
