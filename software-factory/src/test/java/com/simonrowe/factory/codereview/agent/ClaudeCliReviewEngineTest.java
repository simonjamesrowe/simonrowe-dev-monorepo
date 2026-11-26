package com.simonrowe.factory.codereview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.claude.ClaudeCliRunner;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.PriorFinding;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ClaudeCliReviewEngineTest {

  private static final String RAISED_AT = "a".repeat(40);

  private static final ReviewFinding OPEN_FINDING =
      new ReviewFinding(
          Severity.WARNING, "src/App.java", 12, "Null result is dereferenced", "Why.", "Fix.");
  private static final ReviewFinding DECLINED_FINDING =
      new ReviewFinding(
          Severity.CRITICAL, "src/App.java", 30, "Retry loop never ends", "Why.", "Fix.");

  private static final PriorFinding OPEN =
      new PriorFinding(
          FindingFingerprint.of(OPEN_FINDING), OPEN_FINDING, PriorFinding.State.OPEN, RAISED_AT,
          null);
  private static final PriorFinding DECLINED =
      new PriorFinding(
          FindingFingerprint.of(DECLINED_FINDING),
          DECLINED_FINDING,
          PriorFinding.State.DECLINED,
          RAISED_AT,
          "simonrowe");

  private static final Predicate<PriorFinding> TOUCHED = finding -> false;
  private static final Predicate<PriorFinding> UNTOUCHED = finding -> true;

  private final ClaudeCliReviewEngine engine =
      new ClaudeCliReviewEngine(
          properties(),
          mock(GitWorkspaceFactory.class),
          new ClaudeCliRunner(mock(ProcessRunner.class), new ObjectMapper()),
          new ObjectMapper(),
          mock(ReviewGuidance.class));

  @Test
  void validatesChangedPathsDeduplicatesAndNormalizesVerdict() throws Exception {
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
    ClaudeCliReviewEngine.EngineReport raw =
        new ObjectMapper().readValue(structuredOutput, ClaudeCliReviewEngine.EngineReport.class);

    ReviewReport report = engine.postProcess(raw, List.of("src/App.java"), List.of(), TOUCHED);

    assertThat(report.verdict()).isEqualTo(Verdict.COMMENT);
    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().getFirst().severity()).isEqualTo(Severity.WARNING);
    assertThat(report.findings().getFirst().file()).isEqualTo("src/App.java");
  }

  /** The whole point of priorId: a reworded title must keep the thread it already has. */
  @Test
  void rewordedFindingNamingAnOpenPriorKeepsItsIdentity() {
    ReviewReport report =
        engine.postProcess(
            raw(finding("src/App.java", "Possible NPE when lookup misses", OPEN.shortId())),
            List.of("src/App.java"),
            List.of(OPEN),
            TOUCHED);

    assertThat(report.findings()).hasSize(1);
    assertThat(report.findings().getFirst().title()).isEqualTo(OPEN_FINDING.title());
    assertThat(FindingFingerprint.of(report.findings().getFirst()))
        .isEqualTo(OPEN.fingerprint());
  }

  @Test
  void priorIdNamingFindingInAnotherFileIsIgnored() {
    ReviewReport report =
        engine.postProcess(
            raw(finding("src/Other.java", "Something else", OPEN.shortId())),
            List.of("src/App.java", "src/Other.java"),
            List.of(OPEN),
            TOUCHED);

    assertThat(report.findings().getFirst().title()).isEqualTo("Something else");
  }

  @Test
  void priorIdTooShortToBeSpecificIsIgnored() {
    ReviewReport report =
        engine.postProcess(
            raw(finding("src/App.java", "Something else", OPEN.fingerprint().substring(0, 4))),
            List.of("src/App.java"),
            List.of(OPEN),
            TOUCHED);

    assertThat(report.findings().getFirst().title()).isEqualTo("Something else");
  }

  @Test
  void declinedFindingIsDroppedWhetherNamedByIdOrByTitle() {
    ReviewReport report =
        engine.postProcess(
            raw(
                finding("src/App.java", "Unbounded retry", DECLINED.shortId()),
                finding("src/App.java", DECLINED_FINDING.title(), null)),
            List.of("src/App.java"),
            List.of(DECLINED),
            TOUCHED);

    assertThat(report.findings()).isEmpty();
    assertThat(report.verdict()).isEqualTo(Verdict.APPROVE);
  }

  /** The model leaving a finding out is not evidence it was fixed when nothing changed there. */
  @Test
  void openFindingInAnUntouchedFileIsCarriedForward() {
    ReviewReport report =
        engine.postProcess(raw(), List.of("src/App.java"), List.of(OPEN), UNTOUCHED);

    assertThat(report.findings()).containsExactly(OPEN_FINDING);
    assertThat(report.verdict()).isEqualTo(Verdict.COMMENT);
  }

  @Test
  void openFindingInChangedFileIsNotCarried() {
    ReviewReport report =
        engine.postProcess(raw(), List.of("src/App.java"), List.of(OPEN), TOUCHED);

    assertThat(report.findings()).isEmpty();
  }

  /** A file reverted to its base is no longer in the pull request; the finding has gone. */
  @Test
  void openFindingWhoseFileLeftThePullRequestIsNotCarried() {
    ReviewReport report =
        engine.postProcess(raw(), List.of("src/Other.java"), List.of(OPEN), UNTOUCHED);

    assertThat(report.findings()).isEmpty();
  }

  @Test
  void declinedFindingIsNeverCarried() {
    ReviewReport report =
        engine.postProcess(raw(), List.of("src/App.java"), List.of(DECLINED), UNTOUCHED);

    assertThat(report.findings()).isEmpty();
  }

  @Test
  void carriedCriticalStillRequestsChanges() {
    ReviewFinding critical =
        new ReviewFinding(Severity.CRITICAL, "src/App.java", 5, "Data loss", "Why.", "Fix.");
    PriorFinding open =
        new PriorFinding(
            FindingFingerprint.of(critical), critical, PriorFinding.State.OPEN, RAISED_AT, null);

    ReviewReport report =
        engine.postProcess(raw(), List.of("src/App.java"), List.of(open), UNTOUCHED);

    assertThat(report.verdict()).isEqualTo(Verdict.REQUEST_CHANGES);
  }

  @Test
  void untouchedAsksGitOncePerCommitAndTreatsUnknownAsTouched() {
    GitWorkspaceFactory factory = mock(GitWorkspaceFactory.class);
    ClaudeCliReviewEngine withGit =
        new ClaudeCliReviewEngine(
            properties(),
            factory,
            new ClaudeCliRunner(mock(ProcessRunner.class), new ObjectMapper()),
            new ObjectMapper(),
            mock(ReviewGuidance.class));
    String unknownCommit = "b".repeat(40);
    when(factory.changedSince(any(), any(), eq(RAISED_AT), any()))
        .thenReturn(Set.of("src/Other.java"));
    when(factory.changedSince(any(), any(), eq(unknownCommit), any())).thenReturn(null);
    Predicate<PriorFinding> untouched =
        withGit.untouchedSinceRaised(null, pullRequest(), detail -> { });

    assertThat(untouched.test(OPEN)).isTrue();
    assertThat(untouched.test(DECLINED)).isTrue();
    assertThat(
            untouched.test(
                new PriorFinding(
                    OPEN.fingerprint(), OPEN_FINDING, PriorFinding.State.OPEN, unknownCommit,
                    null)))
        .isFalse();
    assertThat(
            untouched.test(
                new PriorFinding(
                    OPEN.fingerprint(), OPEN_FINDING, PriorFinding.State.OPEN, null, null)))
        .isFalse();
    verify(factory, times(1)).changedSince(any(), any(), eq(RAISED_AT), any());
  }

  @Test
  void promptListsPriorFindingsWithTheirIdsAndStates() {
    String section = ClaudeCliReviewEngine.priorSection(List.of(OPEN, DECLINED));

    assertThat(section)
        .contains("id " + OPEN.shortId() + " [open, warning] src/App.java")
        .contains("id " + DECLINED.shortId() + " [declined, critical] src/App.java")
        .contains("Never report a declined finding again");
  }

  @Test
  void promptHasNoPriorOrStandardsSectionWhenThereIsNothingToSay() {
    assertThat(ClaudeCliReviewEngine.priorSection(List.of())).isEmpty();
    assertThat(ClaudeCliReviewEngine.standardsSection("  ")).isEmpty();
  }

  @Test
  void standardsAreFramedAsTrustedReviewCriteria() {
    assertThat(ClaudeCliReviewEngine.standardsSection("### From CLAUDE.md\n\n- Rule"))
        .contains("not from this pull request")
        .contains("- Rule");
  }

  private static ClaudeCliReviewEngine.EngineReport raw(
      final ClaudeCliReviewEngine.EngineFinding... findings) {
    return new ClaudeCliReviewEngine.EngineReport("Summary.", Verdict.APPROVE, List.of(findings));
  }

  private static ClaudeCliReviewEngine.EngineFinding finding(
      final String file, final String title, final String priorId) {
    return new ClaudeCliReviewEngine.EngineFinding(
        Severity.WARNING, file, 14, title, "Why.", "Fix.", priorId);
  }

  private static PullRequestContext pullRequest() {
    return new PullRequestContext(
        "owner", "repo", 7, "Title", "Body", "https://github.com/owner/repo.git",
        "c".repeat(40), "d".repeat(40), null);
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
        new CodeReviewProperties.Api("", null), "https://temporal.test");
  }
}
