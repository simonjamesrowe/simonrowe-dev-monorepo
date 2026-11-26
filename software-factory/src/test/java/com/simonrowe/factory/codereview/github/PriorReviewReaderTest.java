package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.codereview.domain.ExistingThread;
import com.simonrowe.factory.codereview.domain.FindingFingerprint;
import com.simonrowe.factory.codereview.domain.PriorFinding;
import com.simonrowe.factory.codereview.domain.PriorReview;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which earlier findings the next review keeps the identity of, and which a person declined. */
class PriorReviewReaderTest {

  private static final ReviewFinding FINDING =
      new ReviewFinding(Severity.CRITICAL, "src/App.java", 12, "Bad", "Because.", "Fix it.");
  private static final String FP = FindingFingerprint.of(FINDING);

  private static final Map<String, String> PERMISSIONS =
      Map.of("owner", "admin", "writer", "write", "author", "read");

  @Test
  void openThreadIsOpenFinding() {
    PriorReview prior = read(thread("T1", false, null));

    assertThat(prior.findings())
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.state()).isEqualTo(PriorFinding.State.OPEN);
              assertThat(finding.finding()).isEqualTo(FINDING);
              assertThat(finding.raisedAt()).isEqualTo("abc");
            });
    assertThat(prior.declinedFingerprints()).isEmpty();
  }

  @Test
  void threadResolvedByPersonWithWriteAccessIsDeclined() {
    PriorReview prior = read(thread("T1", true, "writer"));

    assertThat(prior.declinedFingerprints()).containsExactly(FP);
    assertThat(prior.findings().getFirst().resolvedBy()).isEqualTo("writer");
  }

  /**
   * A pull request's author may resolve conversations on it without write access. That must not
   * be enough to clear a finding the merge gate holds.
   */
  @Test
  void threadResolvedBySomeoneWithoutWriteAccessIsNotDeclined() {
    assertThat(read(thread("T1", true, "author")).findings()).isEmpty();
    assertThat(read(thread("T1", true, "stranger")).findings()).isEmpty();
  }

  /**
   * Push a fix, then resolve, with no reply: the documented way to clear a fixed finding. It must
   * stay re-checkable, or a fix that did not work would never be flagged again and resolving a
   * critical finding would clear the gate.
   */
  @Test
  void threadResolvedWithoutReplyIsFixNotDecline() {
    ExistingThread fixed =
        new ExistingThread("T1", FP, false, true, false, "writer", "abc", FINDING);

    assertThat(read(fixed).findings()).isEmpty();
    assertThat(read(fixed).declinedFingerprints()).isEmpty();
  }

  /** This reviewer resolves threads itself when a finding goes; that is not a decline. */
  @Test
  void threadThisReviewerResolvedIsNeitherOpenNorDeclined() {
    assertThat(read(thread("T1", true, null)).findings()).isEmpty();
  }

  /**
   * Before this change a declined finding was posted again, so a pull request can hold a declined
   * thread and a newer open one for the same finding. The person's decision wins.
   */
  @Test
  void declineWinsOverAnOpenThreadForTheSameFinding() {
    PriorReview prior = read(thread("T1", true, "owner"), thread("T2", false, null));

    assertThat(prior.findings())
        .singleElement()
        .satisfies(finding -> assertThat(finding.state()).isEqualTo(PriorFinding.State.DECLINED));
  }

  @Test
  void threadsThatAreNotThisReviewersFindingsAreIgnored() {
    ExistingThread human = new ExistingThread("H1", null, false, false, false);
    ExistingThread unreadable =
        new ExistingThread("U1", FP, false, false, false, null, "abc", null);

    assertThat(read(human, unreadable).findings()).isEmpty();
    assertThat(read(human, unreadable).threads()).hasSize(2);
  }

  private static PriorReview read(final ExistingThread... threads) {
    return PriorReviewReader.of(
        List.of(threads), login -> PERMISSIONS.getOrDefault(login, "none"));
  }

  /** A thread a person replied in, as the documented decline leaves it. */
  private static ExistingThread thread(
      final String id, final boolean resolved, final String resolvedBy) {
    return new ExistingThread(id, FP, false, resolved, true, resolvedBy, "abc", FINDING);
  }
}
