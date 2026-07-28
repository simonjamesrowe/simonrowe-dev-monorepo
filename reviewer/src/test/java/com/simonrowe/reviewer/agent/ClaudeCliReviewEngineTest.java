package com.simonrowe.reviewer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.reviewer.config.ReviewerProperties;
import com.simonrowe.reviewer.domain.Severity;
import com.simonrowe.reviewer.domain.Verdict;
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
                "REVIEWER_TRIGGER_TOKEN",
                "TEMPORAL_DB_PASSWORD",
                "PATH"));

    assertThat(removed)
        .containsExactlyInAnyOrder(
            "GITHUB_WEBHOOK_SECRET", "REVIEWER_TRIGGER_TOKEN", "TEMPORAL_DB_PASSWORD");
  }

  private static ReviewerProperties properties() {
    return new ReviewerProperties(
        new ReviewerProperties.Github(
            "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
        new ReviewerProperties.Agent(
            "claude",
            "sonnet",
            "medium",
            12,
            Duration.ofMinutes(15),
            Path.of("/tmp/reviewer-test"),
            2_097_152,
            80,
            "v1"),
        new ReviewerProperties.Api(""));
  }
}
