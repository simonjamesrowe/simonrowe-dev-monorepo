package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FilingDecisionTest {

  private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant NEW = Instant.parse("2026-06-01T00:00:00Z");

  private static TrackedIssue issue(final String id, final IssueStateType type, final Instant at) {
    return new TrackedIssue(id, "SIM-" + id, "https://linear.app/i/" + id, type, at);
  }

  @Test
  void filesNewWhenNothingCarriesTheFingerprint() {
    FilingDecider.Outcome outcome = new FilingDecider().decide(List.of());
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(outcome.subject()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = IssueStateType.class,
      names = {"TRIAGE", "BACKLOG", "UNSTARTED", "STARTED"})
  void commentsOnAnyOpenIssue(final IssueStateType openType) {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", openType, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void suppressesWhenTheOnlyIssueWasCancelled() {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.CANCELED, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void filesRegressionWhenTheOnlyIssueWasCompleted() {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.COMPLETED, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void openOutranksCancelled() {
    // This is the un-suppress gesture: reopening a cancelled issue makes the sink resume
    // reporting, with no config flag.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("cancelled", IssueStateType.CANCELED, OLD),
                    issue("open", IssueStateType.STARTED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("open");
  }

  @Test
  void openOutranksCancelledEvenWhenCancelledIsNewer() {
    // Same rule as openOutranksCancelled, but with the timestamps inverted so a recency-first
    // implementation cannot pass by coincidence: the open issue is older, yet still wins the
    // band. Getting this wrong would swallow a real regression as a stale suppression.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("open", IssueStateType.STARTED, OLD),
                    issue("cancelled", IssueStateType.CANCELED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("open");
  }

  @Test
  void cancelledOutranksCompleted() {
    // A regression was filed for a completed issue, then declined. "Never tell me again" is a
    // more deliberate human statement than "this was once fixed".
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("done", IssueStateType.COMPLETED, OLD),
                    issue("declined", IssueStateType.CANCELED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(outcome.subject().id()).isEqualTo("declined");
  }

  @Test
  void cancelledOutranksCompletedEvenWhenCompletedIsNewer() {
    // Same rule as cancelledOutranksCompleted, but with the timestamps inverted so a
    // recency-first implementation cannot pass by coincidence: the cancelled issue is older,
    // yet still wins the band. Getting this wrong would re-file an issue a human declined,
    // forever.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("declined", IssueStateType.CANCELED, OLD),
                    issue("done", IssueStateType.COMPLETED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(outcome.subject().id()).isEqualTo("declined");
  }

  @Test
  void suppressesWhenTheOnlyIssueWasDuplicate() {
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.DUPLICATE, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(outcome.subject().id()).isEqualTo("1");
  }

  @Test
  void duplicateOutranksCompletedEvenWhenCompletedIsNewer() {
    // Same rule as cancelledOutranksCompletedEvenWhenCompletedIsNewer: duplicate suppresses in
    // the same precedence band as cancelled, so a completed issue that is newer still loses.
    // Getting this wrong would re-file an issue a human closed as a duplicate, forever.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("duplicate", IssueStateType.DUPLICATE, OLD),
                    issue("done", IssueStateType.COMPLETED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(outcome.subject().id()).isEqualTo("duplicate");
  }

  @Test
  void openOutranksDuplicateEvenWhenDuplicateIsNewer() {
    // Same rule as openOutranksCancelledEvenWhenCancelledIsNewer, but for duplicate: the open
    // issue is older, yet still wins the band.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("open", IssueStateType.STARTED, OLD),
                    issue("duplicate", IssueStateType.DUPLICATE, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(outcome.subject().id()).isEqualTo("open");
  }

  @Test
  void picksTheNewestAmongSeveralOpenIssues() {
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("older", IssueStateType.BACKLOG, OLD),
                    issue("newer", IssueStateType.TRIAGE, NEW)));
    assertThat(outcome.subject().id()).isEqualTo("newer");
  }

  @Test
  void picksTheNewestAmongSeveralCompletedIssues() {
    // The regression path leaves two issues sharing one fingerprint, so this is reachable in
    // production, not a hypothetical.
    FilingDecider.Outcome outcome =
        new FilingDecider()
            .decide(
                List.of(
                    issue("first", IssueStateType.COMPLETED, OLD),
                    issue("second", IssueStateType.COMPLETED, NEW)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    assertThat(outcome.subject().id()).isEqualTo("second");
  }

  @Test
  void treatsAnUnrecognisedStateAsOpenRatherThanIgnoringIt() {
    // A state type Linear adds later must not silently become "file another one".
    FilingDecider.Outcome outcome =
        new FilingDecider().decide(List.of(issue("1", IssueStateType.UNKNOWN, OLD)));
    assertThat(outcome.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
  }

  @Test
  void mapsLinearStateTypeStrings() {
    assertThat(IssueStateType.from("triage")).isEqualTo(IssueStateType.TRIAGE);
    assertThat(IssueStateType.from("started")).isEqualTo(IssueStateType.STARTED);
    assertThat(IssueStateType.from("completed")).isEqualTo(IssueStateType.COMPLETED);
    assertThat(IssueStateType.from("canceled")).isEqualTo(IssueStateType.CANCELED);
    assertThat(IssueStateType.from("cancelled")).isEqualTo(IssueStateType.CANCELED);
    assertThat(IssueStateType.from("duplicate")).isEqualTo(IssueStateType.DUPLICATE);
    assertThat(IssueStateType.from("something-new")).isEqualTo(IssueStateType.UNKNOWN);
    assertThat(IssueStateType.from(null)).isEqualTo(IssueStateType.UNKNOWN);
  }

  @ParameterizedTest
  @EnumSource(IssueStateType.class)
  void everyStateIsOpenOrCompletedOrCanceledOrDuplicate(final IssueStateType type) {
    // IssueStateType.open() is exhaustively partitioned today: false means exactly COMPLETED,
    // CANCELED, or DUPLICATE. FilingDecider relies on that partition — its regression band is
    // "whatever is left after open, cancelled and duplicate are removed" rather than an explicit
    // COMPLETED filter. A new closed constant that is none of those three, or flipping UNKNOWN to
    // closed, would silently fall into the regression band and re-file forever with no other test
    // catching it.
    assertThat(
            type.open()
                || type == IssueStateType.COMPLETED
                || type == IssueStateType.CANCELED
                || type == IssueStateType.DUPLICATE)
        .isTrue();
  }
}
