package com.simonrowe.factory.linear.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearIssueRecordTest {

  private static final Instant T0 = Instant.parse("2026-08-27T10:00:00Z");

  private static LinearIssueRecord fresh() {
    return LinearIssueRecord.first(
        "fp", "deploy", List.of("recreate", "backend"), T0);
  }

  private static LinearIssueDecision decision(final String occurrenceId) {
    return new LinearIssueDecision(
        T0, FilingDecision.COMMENTED_EXISTING, occurrenceId, "deploy-prod", "recreate failed",
        false);
  }

  private static LinearIssueDecision dryRunDecision(final String occurrenceId) {
    return new LinearIssueDecision(
        T0, FilingDecision.COMMENTED_EXISTING, occurrenceId, "deploy-prod", "recreate failed",
        true);
  }

  @Test
  void firstRecordHasNoIssueYetAndNoOccurrenceCountedYet() {
    LinearIssueRecord record = fresh();
    assertThat(record.id()).isEqualTo("fp");
    assertThat(record.issueId()).isNull();
    assertThat(record.attachmentPending()).isFalse();
    assertThat(record.occurrences()).isEqualTo(0);
    assertThat(record.decisions()).isEmpty();
  }

  @Test
  void recognisesReplayedOccurrence() {
    LinearIssueRecord record =
        fresh().withDecision(decision("run-1"), T0, IssueStateType.TRIAGE);
    assertThat(record.hasOccurrence("run-1")).isTrue();
    assertThat(record.hasOccurrence("run-2")).isFalse();
  }

  @Test
  void nullOccurrenceIdNeverMatches() {
    // Belt and braces: a producer that forgets to pass one must get "not seen", not a
    // silent no-op that loses the occurrence.
    assertThat(fresh().withDecision(decision(null), T0, IssueStateType.TRIAGE)
            .hasOccurrence(null))
        .isFalse();
  }

  @Test
  void countsOccurrencesAndAdvancesLastSeen() {
    Instant later = T0.plusSeconds(3600);
    LinearIssueRecord record =
        fresh().withDecision(decision("run-1"), later, IssueStateType.STARTED);
    assertThat(record.occurrences()).isEqualTo(1);
    assertThat(record.lastSeenAt()).isEqualTo(later);
    assertThat(record.firstFiledAt()).isEqualTo(T0);
    assertThat(record.lastKnownStateType()).isEqualTo(IssueStateType.STARTED);
  }

  @Test
  void hasOccurrenceIgnoresDryRunEntries() {
    // A dry run audits what would have happened, not what did, so it must never stand in for
    // a real retry of the same occurrence — otherwise switching dryRun off mid-backoff would
    // let the replay guard return the hypothetical decision instead of actually filing.
    LinearIssueRecord record =
        fresh().withDecision(dryRunDecision("run-1"), T0, IssueStateType.TRIAGE);
    assertThat(record.hasOccurrence("run-1")).isFalse();
  }

  @Test
  void capsTheDecisionLogAtTwentyKeepingTheNewest() {
    LinearIssueRecord record = fresh();
    for (int i = 0; i < 25; i++) {
      record = record.withDecision(decision("run-" + i), T0, IssueStateType.TRIAGE);
    }
    assertThat(record.decisions()).hasSize(LinearIssueRecord.MAX_DECISIONS);
    assertThat(record.decisions().get(0).occurrenceId()).isEqualTo("run-5");
    assertThat(record.decisions().get(19).occurrenceId()).isEqualTo("run-24");
    // The counter is not capped, only the log.
    assertThat(record.occurrences()).isEqualTo(25);
  }

  @Test
  void withIssueCarriesEveryOtherFieldThroughUnchangedAndLeavesAttachmentPendingFalse() {
    LinearIssueRecord seeded =
        fresh()
            .withDecision(decision("run-1"), T0.plusSeconds(60), IssueStateType.STARTED)
            .withPendingAttachment("old-id", "SIM-7", "https://linear.app/i/7")
            .withAttachmentWritten();

    LinearIssueRecord pointedElsewhere =
        seeded.withIssue("i9", "SIM-9", "https://linear.app/i/9");

    assertThat(pointedElsewhere.issueId()).isEqualTo("i9");
    assertThat(pointedElsewhere.issueIdentifier()).isEqualTo("SIM-9");
    assertThat(pointedElsewhere.issueUrl()).isEqualTo("https://linear.app/i/9");
    assertThat(pointedElsewhere.attachmentPending()).isFalse();
    // Everything else carries through unchanged.
    assertThat(pointedElsewhere.id()).isEqualTo(seeded.id());
    assertThat(pointedElsewhere.producer()).isEqualTo(seeded.producer());
    assertThat(pointedElsewhere.fingerprintVersion()).isEqualTo(seeded.fingerprintVersion());
    assertThat(pointedElsewhere.keyParts()).isEqualTo(seeded.keyParts());
    assertThat(pointedElsewhere.firstFiledAt()).isEqualTo(seeded.firstFiledAt());
    assertThat(pointedElsewhere.lastSeenAt()).isEqualTo(seeded.lastSeenAt());
    assertThat(pointedElsewhere.occurrences()).isEqualTo(seeded.occurrences());
    assertThat(pointedElsewhere.lastKnownStateType()).isEqualTo(seeded.lastKnownStateType());
    assertThat(pointedElsewhere.decisions()).isEqualTo(seeded.decisions());
  }

  @Test
  void cappedOutOccurrenceIsNoLongerRecognised() {
    // Known and accepted: beyond 20 decisions a replay could post a duplicate comment. The
    // window is far wider than any activity retry, and one duplicate comment is a benign
    // failure compared with the cost of an unbounded array.
    LinearIssueRecord record = fresh();
    for (int i = 0; i < 25; i++) {
      record = record.withDecision(decision("run-" + i), T0, IssueStateType.TRIAGE);
    }
    assertThat(record.hasOccurrence("run-0")).isFalse();
    assertThat(record.hasOccurrence("run-24")).isTrue();
  }
}
