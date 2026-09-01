package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCliReviewEngineTest {

  @Test
  void validatesChangedPathsDeduplicatesAndNormalizesVerdict() throws Exception {
    ClaudeCliReviewEngine engine =
        new ClaudeCliReviewEngine(
            properties(),
            mock(GitWorkspaceFactory.class),
            new ClaudeCliRunner(mock(ProcessRunner.class), new ObjectMapper()),
            new ObjectMapper());
    String structuredOutput =
        """
        {
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
        """;
    ReviewReport raw = new ObjectMapper().readValue(structuredOutput, ReviewReport.class);

    ReviewReport report = engine.postProcess(raw, List.of("src/App.java"));

    assertThat(report.verdict()).isEqualTo(Verdict.COMMENT);
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().getFirst().severity()).isEqualTo(Severity.WARNING);
    assertThat(report.findings().getFirst().file()).isEqualTo("src/App.java");
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
        new CodeReviewProperties.Api(""), "https://temporal.test");
  }
}
