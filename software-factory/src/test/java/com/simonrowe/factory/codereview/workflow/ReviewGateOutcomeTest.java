package com.simonrowe.factory.codereview.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.PriorFinding;
import com.simonrowe.factory.codereview.domain.PriorReview;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.github.CheckRunGateway;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When resolving a thread may turn the Code Review check green. It may only ever do that, and only
 * when every finding that failed the check was declined by someone allowed to.
 */
class ReviewGateOutcomeTest {

  private static final ReviewFinding FIRST =
      new ReviewFinding(Severity.CRITICAL, "a.java", 1, "First", "Why.", "Fix.");
  private static final ReviewFinding SECOND =
      new ReviewFinding(Severity.CRITICAL, "b.java", 2, "Second", "Why.", "Fix.");

  @Test
  void turnsGreenWhenEveryFailingFindingWasDeclined() {
    assertThat(outcome(failed(FIRST), declined(FIRST))).isNull();
  }

  @Test
  void staysRedWhileAnyFailingFindingIsNotDeclined() {
    assertThat(outcome(failed(FIRST, SECOND), declined(FIRST)))
        .isEqualTo("left alone: 1 critical finding(s) not declined");
  }

  /** A review that failed recorded no findings; nothing a person resolves can clear that. */
  @Test
  void failedReviewIsNeverTurnedGreen() {
    assertThat(outcome(new CheckRunGateway.Gate(1, true, "failure", null), declined(FIRST)))
        .contains("cannot clear");
    assertThat(outcome(new CheckRunGateway.Gate(1, true, "failure", Set.of()), declined(FIRST)))
        .contains("cannot clear");
  }

  @Test
  void reviewInProgressIsLeftToReadTheDeclineItself() {
    assertThat(outcome(new CheckRunGateway.Gate(1, false, "", null), declined(FIRST)))
        .contains("in progress");
  }

  @Test
  void passingCheckIsLeftAlone() {
    assertThat(outcome(new CheckRunGateway.Gate(1, true, "success", Set.of()), declined(FIRST)))
        .contains("not failing");
  }

  @Test
  void noCheckAtAllIsLeftAlone() {
    assertThat(
            ReviewActivitiesImpl.gateOutcome(
                Optional.empty(), () -> declined(FIRST)))
        .contains("no Code Review check");
  }

  private static String outcome(final CheckRunGateway.Gate gate, final PriorReview prior) {
    return ReviewActivitiesImpl.gateOutcome(Optional.of(gate), () -> prior);
  }

  private static CheckRunGateway.Gate failed(final ReviewFinding... findings) {
    return new CheckRunGateway.Gate(
        1,
        true,
        "failure",
        Set.copyOf(List.of(findings).stream().map(FindingFingerprint::of).toList()));
  }

  private static PriorReview declined(final ReviewFinding finding) {
    return new PriorReview(
        List.of(),
        List.of(
            new PriorFinding(
                FindingFingerprint.of(finding),
                finding,
                PriorFinding.State.DECLINED,
                null,
                "simonrowe")));
  }
}
