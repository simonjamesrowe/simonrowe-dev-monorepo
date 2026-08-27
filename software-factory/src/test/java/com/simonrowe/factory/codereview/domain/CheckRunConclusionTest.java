package com.simonrowe.factory.codereview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The conclusion mapping is the whole gate. Every verdict is tested against both the presence and
 * the absence of a critical finding, because the interesting cases are the ones where the two
 * disagree.
 */
class CheckRunConclusionTest {

  private static ReviewFinding finding(final Severity severity) {
    return new ReviewFinding(severity, "src/App.java", 12, "Bad", "Because.", "Fix it.");
  }

  @ParameterizedTest(name = "{0} with critical={1} is {2}")
  @CsvSource({
      "APPROVE,         false, SUCCESS",
      "APPROVE,         true,  FAILURE",
      "COMMENT,         false, SUCCESS",
      "COMMENT,         true,  FAILURE",
      "REQUEST_CHANGES, false, FAILURE",
      "REQUEST_CHANGES, true,  FAILURE",
  })
  void everyVerdictBySeverityCombination(
      final Verdict verdict, final boolean critical, final CheckRunConclusion expected) {
    List<ReviewFinding> findings =
        critical
            ? List.of(finding(Severity.WARNING), finding(Severity.CRITICAL))
            : List.of(finding(Severity.WARNING), finding(Severity.SUGGESTION));

    assertThat(CheckRunConclusion.from(verdict, findings)).isEqualTo(expected);
  }

  /**
   * The case that justifies checking both conditions rather than trusting the verdict. The engine
   * grades the summary and the individual findings in the same pass but not necessarily
   * consistently, so it can approve while reporting something critical. The finding wins.
   */
  @Test
  void approvingWhileReportingSomethingCriticalIsStillRedCheck() {
    assertThat(CheckRunConclusion.from(Verdict.APPROVE, List.of(finding(Severity.CRITICAL))))
        .isEqualTo(CheckRunConclusion.FAILURE);
  }

  @Test
  void requestingChangesIsRedEvenWithNoFindingsAtAll() {
    assertThat(CheckRunConclusion.from(Verdict.REQUEST_CHANGES, List.of()))
        .isEqualTo(CheckRunConclusion.FAILURE);
  }

  @ParameterizedTest
  @EnumSource(value = Verdict.class, names = {"APPROVE", "COMMENT"})
  void cleanReviewIsGreen(final Verdict verdict) {
    assertThat(CheckRunConclusion.from(verdict, List.of())).isEqualTo(CheckRunConclusion.SUCCESS);
  }

  @Test
  void warningsAndSuggestionsAloneDoNotTurnTheCheckRed() {
    // They block the merge through required conversation resolution instead — a deliberately
    // separate mechanism, so the gate does not depend on the model grading severity correctly.
    assertThat(
            CheckRunConclusion.from(
                Verdict.COMMENT, List.of(finding(Severity.WARNING), finding(Severity.SUGGESTION))))
        .isEqualTo(CheckRunConclusion.SUCCESS);
  }

  @Test
  void nullFindingsAreTreatedAsNone() {
    assertThat(CheckRunConclusion.from(Verdict.APPROVE, null))
        .isEqualTo(CheckRunConclusion.SUCCESS);
  }

  /**
   * Guards the design decision as much as the code. GitHub also offers {@code neutral}, but whether
   * it satisfies a required status check is version-dependent behaviour, and this check stands
   * between a critical finding and the default branch. Adding a third constant must fail a test.
   */
  @Test
  void onlySuccessAndFailureExist() {
    assertThat(CheckRunConclusion.values())
        .containsExactly(CheckRunConclusion.SUCCESS, CheckRunConclusion.FAILURE);
  }

  @Test
  void rendersTheValuesGitHubExpects() {
    assertThat(CheckRunConclusion.SUCCESS.toJson()).isEqualTo("success");
    assertThat(CheckRunConclusion.FAILURE.toJson()).isEqualTo("failure");
  }
}
