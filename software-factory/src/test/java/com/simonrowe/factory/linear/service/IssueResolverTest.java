package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class IssueResolverTest {

  private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
  private static final Duration QUIET_FOR = Duration.ofDays(7);
  private static final String PRODUCER = "logwatch";

  private final LinearGateway gateway = mock(LinearGateway.class);
  private final LinearIssueRepository records = mock(LinearIssueRepository.class);
  private LinearProperties properties;

  @BeforeEach
  void setUp() {
    properties = properties(false);
    when(records.save(any(LinearIssueRecord.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gateway.teamContext())
        .thenReturn(new LinearGateway.TeamContext("t1", "triage-state", "done-state", Map.of()));
  }

  @Test
  @DisplayName("a problem nobody has reported for the quiet period is commented on, then closed")
  void closesTheQuietIssue() {
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.TRIAGE)));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.considered()).isEqualTo(1);
    assertThat(report.resolved()).hasSize(1);
    assertThat(report.resolved().get(0).issueIdentifier()).isEqualTo("SIM-30");
    assertThat(report.resolved().get(0).keyParts()).containsExactly("backend", "ERROR", "boom");

    // Comment BEFORE the state change. The other order leaves a window in which the ticket is
    // closed with no explanation, and that window is exactly when a human reads their inbox.
    InOrder order = inOrder(gateway);
    order.verify(gateway).addComment(eq("issue-boom"), anyString());
    order.verify(gateway).updateIssue("issue-boom", null, "done-state");
  }

  @Test
  @DisplayName("the description is left alone, so closing a ticket never erases what it said")
  void neverRewritesTheDescription() {
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.TRIAGE)));

    resolver().sweep(sweep());

    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
    verify(gateway).updateIssue(anyString(), description.capture(), anyString());
    assertThat(description.getValue()).isNull();
  }

  @Test
  @DisplayName("a problem seen inside the quiet period is not a candidate at all")
  void leavesRecentlySeenProblemsAlone() {
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER))
        .thenReturn(List.of(record("boom", NOW.minus(Duration.ofDays(2)))));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.considered()).isZero();
    assertThat(report.resolved()).isEmpty();
    verify(gateway, never()).addComment(anyString(), anyString());
    verify(gateway, never()).updateIssue(anyString(), any(), anyString());
  }

  /**
   * The rule that keeps the automation from overruling a person.
   *
   * <p>Someone mid-fix is very often the <em>reason</em> the logs went quiet — a service stopped,
   * a config reverted, a branch deployed — so closing their ticket underneath them would be both
   * rude and, quite often, wrong.
   */
  @Test
  @DisplayName("an issue somebody has started is left alone, and reported separately")
  void neverClosesSomethingBeingWorkedOn() {
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.STARTED)));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.considered()).isEqualTo(1);
    assertThat(report.resolved()).isEmpty();
    assertThat(report.skippedStarted()).isEqualTo(1);
    verify(gateway, never()).addComment(anyString(), anyString());
  }

  @Test
  @DisplayName("an issue a human already closed is counted, not commented on again")
  void doesNotTouchAnAlreadyClosedIssue() {
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.CANCELED)));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.alreadyClosed()).isEqualTo(1);
    assertThat(report.resolved()).isEmpty();
    verify(gateway, never()).addComment(anyString(), anyString());
  }

  /**
   * The stored state is only ever as fresh as the last filing, and for a fingerprint that has
   * gone quiet that is by definition stale. Linear is truth here exactly as it is for filing.
   */
  @Test
  @DisplayName("the state is re-read from Linear, never taken from the stored record")
  void rereadsStateFromLinear() {
    LinearIssueRecord stale =
        record("boom", NOW.minus(Duration.ofDays(9)))
            .withDecision(
                new com.simonrowe.factory.linear.persistence.LinearIssueDecision(
                    NOW.minus(Duration.ofDays(9)),
                    FilingDecision.FILED_NEW,
                    "old-run",
                    "old-workflow",
                    "detail",
                    false),
                NOW.minus(Duration.ofDays(9)),
                IssueStateType.TRIAGE);
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(stale));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(stale.issueId(), IssueStateType.COMPLETED)));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.alreadyClosed()).isEqualTo(1);
    assertThat(report.resolved()).isEmpty();
  }

  @Test
  @DisplayName("a fingerprint with no issue behind it is counted as untracked, not closed")
  void skipsFingerprintsWithNoIssue() {
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER))
        .thenReturn(
            List.of(
                LinearIssueRecord.first(
                    "fp", PRODUCER, List.of("backend", "ERROR", "boom"),
                    NOW.minus(Duration.ofDays(9)))));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.untracked()).isEqualTo(1);
    assertThat(report.resolved()).isEmpty();
    verify(gateway, never()).issuesForFingerprint(anyString());
  }

  /**
   * "Nothing was closed" and "nothing CAN be closed" must not present identically, or a team
   * misconfiguration reads as a permanently clean stack.
   */
  @Test
  @DisplayName("a team with no completed state reports unavailable rather than silence")
  void reportsUnavailableWhenTheTeamHasNoDoneState() {
    when(gateway.teamContext())
        .thenReturn(new LinearGateway.TeamContext("t1", "triage-state", null, Map.of()));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER))
        .thenReturn(List.of(record("boom", NOW.minus(Duration.ofDays(9)))));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.unavailable()).isTrue();
    assertThat(report.considered()).isEqualTo(1);
    assertThat(report.resolved()).isEmpty();
    verify(gateway, never()).updateIssue(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("a configured dry run reports what it would close and writes nothing to Linear")
  void dryRunChangesNothing() {
    properties = properties(true);
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.TRIAGE)));

    SweepReport report = resolver().sweep(sweep());

    assertThat(report.resolved()).hasSize(1);
    verify(gateway, never()).addComment(anyString(), anyString());
    verify(gateway, never()).updateIssue(anyString(), any(), anyString());
  }

  /**
   * The two dry-run flags are independent, and only one of them is set on the path that matters.
   * A manual "Dry run scan" from the console answers "nothing will be filed" while the sink is
   * configured — correctly — to write on every other run. Consulting only
   * {@code factory.linear.dry-run} makes that answer a lie, and the lie is a real ticket moved to
   * Done with a real comment on it.
   */
  @Test
  @DisplayName("a request-level dry run writes nothing even when the sink is configured to write")
  void requestDryRunChangesNothingWithTheSinkLive() {
    properties = properties(false);
    LinearIssueRecord quiet = record("boom", NOW.minus(Duration.ofDays(9)));
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.TRIAGE)));

    SweepReport report =
        resolver()
            .sweep(
                new AbsenceSweep(
                    PRODUCER, QUIET_FOR, "run-9", "logwatch-manual", "no longer seen", true));

    assertThat(report.resolved()).hasSize(1);
    verify(gateway, never()).addComment(anyString(), anyString());
    verify(gateway, never()).updateIssue(anyString(), any(), anyString());

    ArgumentCaptor<LinearIssueRecord> saved = ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().decisions()).last().extracting(
        com.simonrowe.factory.linear.persistence.LinearIssueDecision::dryRun).isEqualTo(true);
  }

  /**
   * {@code lastSeenAt} means "when this problem was last observed". The sweep observed its
   * <em>absence</em>, so advancing it would both reset the quiet clock and make the record claim
   * the problem was happening at the moment it was declared gone.
   */
  @Test
  @DisplayName("closing a ticket does not advance lastSeenAt")
  void doesNotAdvanceLastSeen() {
    Instant lastSeen = NOW.minus(Duration.ofDays(9));
    LinearIssueRecord quiet = record("boom", lastSeen);
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of(quiet));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(open(quiet.issueId(), IssueStateType.TRIAGE)));

    resolver().sweep(sweep());

    ArgumentCaptor<LinearIssueRecord> saved = ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().lastSeenAt()).isEqualTo(lastSeen);
    assertThat(saved.getValue().decisions())
        .last()
        .extracting(com.simonrowe.factory.linear.persistence.LinearIssueDecision::decision)
        .isEqualTo(FilingDecision.RESOLVED_ABSENT);
  }

  /**
   * A sweep must never reach outside the producer that asked for it: an absent {@code cvefix}
   * finding means the CVE was patched, an absent {@code deploy} failure means nothing at all, and
   * neither is log watch's to decide.
   */
  @Test
  @DisplayName("only the requesting producer's records are read")
  void staysWithinItsOwnProducer() {
    when(records.findByProducerOrderByLastSeenAtDesc(PRODUCER)).thenReturn(List.of());

    resolver().sweep(sweep());

    verify(records).findByProducerOrderByLastSeenAtDesc(PRODUCER);
    verify(records, never()).findAll();
  }

  private IssueResolver resolver() {
    return new IssueResolver(
        gateway, records, properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AbsenceSweep sweep() {
    return new AbsenceSweep(
        PRODUCER, QUIET_FOR, "run-9", "logwatch-nightly", "no longer seen", false);
  }

  private static LinearIssueRecord record(final String key, final Instant lastSeen) {
    List<String> keyParts = List.of("backend", "ERROR", key);
    return LinearIssueRecord.first(
            Fingerprint.of(PRODUCER, keyParts), PRODUCER, keyParts, lastSeen)
        .withIssue("issue-" + key, "SIM-30", "https://linear.app/SIM-30");
  }

  private static TrackedIssue open(final String issueId, final IssueStateType state) {
    return new TrackedIssue(issueId, "SIM-30", "https://linear.app/SIM-30", state, NOW);
  }

  private static LinearProperties properties(final boolean dryRun) {
    return new LinearProperties(true, "k", null, "SIM", null, dryRun, null, null);
  }
}
