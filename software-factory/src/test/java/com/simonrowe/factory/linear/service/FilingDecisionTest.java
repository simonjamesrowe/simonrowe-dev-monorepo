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
    assertThat(new FilingDecider().decide(List.of(issue("1", IssueStateType.CANCELED, OLD))))
        .extracting(FilingDecider.Outcome::decision)
        .isEqualTo(FilingDecision.SUPPRESSED);
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
  void cancelledOutranksCompleted() {
    // A regression was filed for a completed issue, then declined. "Never tell me again" is a
    // more deliberate human statement than "this was once fixed".
    assertThat(
            new FilingDecider()
                .decide(
                    List.of(
                        issue("done", IssueStateType.COMPLETED, OLD),
                        issue("declined", IssueStateType.CANCELED, NEW))))
        .extracting(FilingDecider.Outcome::decision)
        .isEqualTo(FilingDecision.SUPPRESSED);
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
    assertThat(IssueStateType.from("something-new")).isEqualTo(IssueStateType.UNKNOWN);
    assertThat(IssueStateType.from(null)).isEqualTo(IssueStateType.UNKNOWN);
  }
}
