package com.simonrowe.factory.codereview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AutoMergePolicyTest {

  private static final String HEAD = "0123456789abcdef";

  private static final ReviewReport CLEAN = new ReviewReport("Fine.", Verdict.APPROVE, List.of());

  private static final ChangedFiles BACKEND_ONLY =
      new ChangedFiles(List.of("backend/src/main/java/A.java", "docs/runbooks/x.md"), true);

  private static AutoMergeState state() {
    return new AutoMergeState(
        "PR_node", HEAD, false, false, "OWNER", List.of(), 2, false, false);
  }

  private static MergeDecision decide(final AutoMergeState state, final ReviewReport report) {
    return AutoMergePolicy.decide(true, state, report, HEAD, () -> BACKEND_ONLY);
  }

  @Test
  void armsCleanOwnerChangeWhosePathsAllQualify() {
    assertThat(decide(state(), CLEAN)).isEqualTo(MergeDecision.eligible());
  }

  @Test
  void membersAndCollaboratorsAreTrustedToo() {
    for (String association : List.of("MEMBER", "COLLABORATOR")) {
      assertThat(decide(withAssociation(association), CLEAN)).isEqualTo(MergeDecision.eligible());
    }
  }

  @Test
  void switchedOffSaysNothingAndListsNoFiles() {
    AtomicBoolean listed = new AtomicBoolean();
    MergeDecision decision =
        AutoMergePolicy.decide(false, state(), CLEAN, HEAD, recording(listed, BACKEND_ONLY));

    assertThat(decision).isEqualTo(MergeDecision.off());
    assertThat(listed).isFalse();
  }

  // --- each rule, alone, says no --------------------------------------------------------------

  @Test
  void draftIsNotArmed() {
    AutoMergeState draft =
        new AutoMergeState("PR_node", HEAD, true, false, "OWNER", List.of(), 2, false, false);
    assertThat(decide(draft, CLEAN).reason()).isEqualTo("the pull request is a draft");
  }

  /** A public repository: a clean review of a stranger's fork must never merge itself. */
  @Test
  void forkIsNotArmedEvenWithCleanReview() {
    AutoMergeState fork =
        new AutoMergeState("PR_node", HEAD, false, true, "OWNER", List.of(), 2, false, false);
    assertThat(decide(fork, CLEAN).reason()).isEqualTo("the change comes from a fork");
  }

  @Test
  void anAuthorWithoutWriteAccessIsNotArmed() {
    for (String association : List.of("CONTRIBUTOR", "FIRST_TIME_CONTRIBUTOR", "NONE", "")) {
      assertThat(decide(withAssociation(association), CLEAN).outcome())
          .as(association)
          .isEqualTo(MergeDecision.Outcome.INELIGIBLE);
    }
    assertThat(decide(withAssociation(null), CLEAN).outcome())
        .isEqualTo(MergeDecision.Outcome.INELIGIBLE);
  }

  @Test
  void theOptOutLabelIsHonoured() {
    assertThat(decide(withLabels("no-auto-merge"), CLEAN).reason())
        .isEqualTo("labelled `no-auto-merge`");
  }

  @Test
  void feedbackGuidanceIsLeftToHuman() {
    assertThat(decide(withLabels("agent-feedback"), CLEAN).reason()).contains("agent-feedback");
  }

  @Test
  void requestChangesIsNotArmed() {
    ReviewReport changes = new ReviewReport("No.", Verdict.REQUEST_CHANGES, List.of());
    assertThat(decide(state(), changes).reason()).isEqualTo("the `Code Review` check is red");
  }

  /** The engine can grade inconsistently; the finding wins, exactly as the check run's does. */
  @Test
  void approveWithCriticalFindingIsNotArmed() {
    ReviewReport inconsistent =
        new ReviewReport(
            "Fine.",
            Verdict.APPROVE,
            List.of(
                new ReviewFinding(
                    Severity.CRITICAL, "backend/A.java", 1, "Leak", "Why.", "Fix.")));
    assertThat(decide(state(), inconsistent).reason()).isEqualTo("the `Code Review` check is red");
  }

  @Test
  void nonCriticalFindingDoesNotStopTheArm() {
    // Required conversation resolution holds the merge on it; repeating that here would be a
    // second answer to a question GitHub already owns.
    ReviewReport suggestion =
        new ReviewReport(
            "Mostly fine.",
            Verdict.COMMENT,
            List.of(
                new ReviewFinding(
                    Severity.SUGGESTION, "backend/A.java", 1, "Name", "Why.", "Rename.")));
    assertThat(decide(state(), suggestion)).isEqualTo(MergeDecision.eligible());
  }

  @Test
  void headThatMovedOnIsNotArmed() {
    AutoMergeState moved =
        new AutoMergeState(
            "PR_node", "fedcba9876543210", false, false, "OWNER", List.of(), 2, false, false);
    assertThat(decide(moved, CLEAN).reason())
        .isEqualTo("the pull request moved on to `fedcba9` after `0123456` was reviewed");
  }

  @Test
  void anIncompleteFileListIsNotArmed() {
    MergeDecision decision =
        AutoMergePolicy.decide(
            true, state(), CLEAN, HEAD, () -> new ChangedFiles(BACKEND_ONLY.paths(), false));
    assertThat(decision.reason()).isEqualTo("the file list is too long to classify (2 files)");
  }

  @Test
  void manualPathIsNamed() {
    MergeDecision decision =
        AutoMergePolicy.decide(
            true,
            state(),
            CLEAN,
            HEAD,
            () -> new ChangedFiles(List.of("backend/A.java", "docker-compose.prod.yml"), true));
    assertThat(decision.reason()).isEqualTo("`docker-compose.prod.yml` needs a human to merge");
  }

  @Test
  void uxPathIsNamed() {
    MergeDecision decision =
        AutoMergePolicy.decide(
            true,
            state(),
            CLEAN,
            HEAD,
            () -> new ChangedFiles(List.of("frontend/src/App.tsx"), true));
    assertThat(decision.reason())
        .isEqualTo("`frontend/src/App.tsx` changes what a visitor sees, so it needs screenshots"
            + " and a human");
  }

  /** Moving a script into docs/ deletes a script; the old side of a rename is classified too. */
  @Test
  void theOldSideOfRenameCounts() {
    MergeDecision decision =
        AutoMergePolicy.decide(
            true,
            state(),
            CLEAN,
            HEAD,
            () -> new ChangedFiles(List.of("docs/x.sh", "scripts/x.sh"), true));
    assertThat(decision.reason()).isEqualTo("`scripts/x.sh` needs a human to merge");
  }

  @Test
  void anEmptyChangeIsNotArmed() {
    MergeDecision decision =
        AutoMergePolicy.decide(true, state(), CLEAN, HEAD, () -> new ChangedFiles(List.of(), true));
    assertThat(decision.reason()).isEqualTo("the change touches no files");
  }

  // --- asymmetric combinations: one side clean, the other not ---------------------------------

  @Test
  void cleanVerdictDoesNotRescueManualPath() {
    MergeDecision decision =
        AutoMergePolicy.decide(
            true,
            state(),
            CLEAN,
            HEAD,
            () -> new ChangedFiles(List.of("scripts/restart-prod.sh"), true));
    assertThat(decision.outcome()).isEqualTo(MergeDecision.Outcome.INELIGIBLE);
  }

  @Test
  void autoMergePathDoesNotRescueRedReview() {
    ReviewReport changes = new ReviewReport("No.", Verdict.REQUEST_CHANGES, List.of());
    assertThat(decide(state(), changes).outcome()).isEqualTo(MergeDecision.Outcome.INELIGIBLE);
  }

  @Test
  void cleanForkWithOnlyDocsIsStillNotArmed() {
    AutoMergeState fork =
        new AutoMergeState("PR_node", HEAD, false, true, "MEMBER", List.of(), 2, false, false);
    assertThat(decide(fork, CLEAN).outcome()).isEqualTo(MergeDecision.Outcome.INELIGIBLE);
  }

  @Test
  void ruleThatFailsEarlyCostsNoFileListing() {
    AtomicBoolean listed = new AtomicBoolean();
    ReviewReport changes = new ReviewReport("No.", Verdict.REQUEST_CHANGES, List.of());

    AutoMergePolicy.decide(true, state(), changes, HEAD, recording(listed, BACKEND_ONLY));

    assertThat(listed).isFalse();
  }

  // --- describe ---------------------------------------------------------------------------------

  @Test
  void describesEachOutcomeForTheProgressLine() {
    assertThat(MergeDecision.off().describe()).isNull();
    assertThat(MergeDecision.armed().describe()).isEqualTo("armed");
    assertThat(MergeDecision.eligible().describe()).isEqualTo("would arm (dry run)");
    assertThat(MergeDecision.ineligible("labelled `x`").describe())
        .isEqualTo("not armed: labelled `x`");
    assertThat(MergeDecision.armFailed("GraphQL 502").describe())
        .isEqualTo("not armed: arming failed (GraphQL 502)");
  }

  private static AutoMergeState withAssociation(final String association) {
    return new AutoMergeState(
        "PR_node", HEAD, false, false, association, List.of(), 2, false, false);
  }

  private static AutoMergeState withLabels(final String... labels) {
    return new AutoMergeState(
        "PR_node", HEAD, false, false, "OWNER", List.of(labels), 2, false, false);
  }

  private static Supplier<ChangedFiles> recording(
      final AtomicBoolean listed, final ChangedFiles files) {
    return () -> {
      listed.set(true);
      return files;
    };
  }
}
