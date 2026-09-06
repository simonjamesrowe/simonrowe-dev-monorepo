package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.FilingMode;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.persistence.LinearIssueDecision;
import com.simonrowe.factory.linear.persistence.LinearIssueRecord;
import com.simonrowe.factory.linear.persistence.LinearIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class IssueFilerTest {

  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

  private final LinearGateway gateway = mock(LinearGateway.class);
  private final LinearIssueRepository records = mock(LinearIssueRepository.class);
  private LinearProperties properties;

  private static IssueFiling filing() {
    return new IssueFiling(
        "deploy",
        List.of("recreate", "backend"),
        "recreate failed on backend",
        "The deploy of deadbeef failed in recreate.",
        "commit deadbeef at 12:00",
        "run-1",
        "deploy-prod");
  }

  private static TrackedIssue issue(final IssueStateType type) {
    return new TrackedIssue("i1", "SIM-1", "https://linear.app/i/1", type, Instant.EPOCH);
  }

  private IssueFiler filer() {
    return new IssueFiler(
        gateway,
        new FilingDecider(),
        records,
        properties,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @BeforeEach
  void configure() {
    properties =
        new LinearProperties(true, "k", null, "SIM", null, false, null, null);
    when(records.save(any(LinearIssueRecord.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(records.findById(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void filesNewThenAttachesTheFingerprint() {
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "https://linear.app/i/9"));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-9");
    assertThat(filed.fingerprint())
        .isEqualTo(Fingerprint.of("deploy", List.of("recreate", "backend")));
    verify(gateway)
        .createIssue(eq("recreate failed on backend"), anyString(), eq(1), eq("factory:deploy"));
    verify(gateway).attachFingerprint(eq("i9"), anyString());

    // The ordering is the entire recovery mechanism: a failure between createIssue and
    // attachFingerprint must be recoverable by attaching, never by filing a second issue. Pin
    // it with InOrder rather than trusting three independent "was it called" verifications,
    // which would stay green even if the save moved below attachFingerprint or was deleted.
    InOrder inOrder = inOrder(gateway, records);
    inOrder.verify(gateway).createIssue(anyString(), anyString(), anyInt(), anyString());
    inOrder.verify(records).save(any(LinearIssueRecord.class));
    inOrder.verify(gateway).attachFingerprint(eq("i9"), anyString());

    ArgumentCaptor<LinearIssueRecord> savedRecords =
        ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records, times(2)).save(savedRecords.capture());
    assertThat(savedRecords.getAllValues().get(0).attachmentPending()).isTrue();
    assertThat(savedRecords.getAllValues().get(0).issueId()).isEqualTo("i9");
    assertThat(savedRecords.getAllValues().get(1).attachmentPending()).isFalse();
  }

  @Test
  void commentsOnOpenIssueInsteadOfFilingSecond() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.STARTED)));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-1");
    verify(gateway).addComment(eq("i1"), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  void doesNothingToLinearWhenHumanDeclinedIt() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.CANCELED)));

    assertThat(filer().file(filing()).decision()).isEqualTo(FilingDecision.SUPPRESSED);
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).addComment(anyString(), anyString());
    // Still audited: "we saw it and stayed quiet" is exactly what the trail is for.
    verify(records).save(any(LinearIssueRecord.class));
  }

  @Test
  void suppressedOccurrenceReportsNoIssueEvenWhenTheRecordStillCarriesOne() {
    // Reachable on the ordinary path, not a hypothetical: occurrence 1 filed SIM-1, a human
    // cancelled SIM-1, occurrence 2 arrives. The SUPPRESSED arm deliberately leaves the record
    // pointing at that issue, so `finish` would otherwise hand the stored identifier and URL
    // back to the producer - and DeployWorkflowImpl would put that URL on a run that filed
    // nothing, with a commit comment inviting a reader to a declined ticket that does not
    // contain this run's diagnosis. "We stayed quiet" and "here is the ticket" are different
    // facts.
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    LinearIssueRecord filedThenCancelled =
        LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
            .withPendingAttachment("i1", "SIM-1", "https://linear.app/i/1")
            .withAttachmentWritten()
            .withDecision(
                new LinearIssueDecision(
                    NOW, FilingDecision.FILED_NEW, "run-0", "deploy-prod", "x", false),
                NOW,
                IssueStateType.TRIAGE);
    when(records.findById(fingerprint)).thenReturn(Optional.of(filedThenCancelled));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.CANCELED)));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(filed.issueIdentifier()).isNull();
    assertThat(filed.issueUrl()).isNull();

    // Only what is RETURNED is blanked. The audit trail keeps the whole history, which is the
    // only place the declined ticket is still recoverable from.
    ArgumentCaptor<LinearIssueRecord> saved = ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().issueIdentifier()).isEqualTo("SIM-1");
    assertThat(saved.getValue().issueUrl()).isEqualTo("https://linear.app/i/1");
    assertThat(saved.getValue().lastKnownStateType()).isEqualTo(IssueStateType.CANCELED);
  }

  @Test
  void replayOfSuppressedOccurrenceAlsoReportsNoIssue() {
    // The replay arm returns the STORED decision, so it needs the same treatment: a retry of a
    // suppressed occurrence must not start reporting a ticket the first attempt correctly
    // withheld.
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    LinearIssueRecord suppressed =
        LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
            .withPendingAttachment("i1", "SIM-1", "https://linear.app/i/1")
            .withAttachmentWritten()
            .withDecision(
                new LinearIssueDecision(
                    NOW, FilingDecision.SUPPRESSED, "run-1", "deploy-prod", "x", false),
                NOW,
                IssueStateType.CANCELED);
    when(records.findById(fingerprint)).thenReturn(Optional.of(suppressed));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SUPPRESSED);
    assertThat(filed.issueIdentifier()).isNull();
    assertThat(filed.issueUrl()).isNull();
    verifyNoInteractions(gateway);
  }

  @Test
  void filesRegressionNamingThePredecessorInTheBody() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "u"));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_REGRESSION);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(gateway).createIssue(anyString(), body.capture(), anyInt(), anyString());
    assertThat(body.getValue()).contains("SIM-1");
    verify(gateway).attachFingerprint(eq("i9"), anyString());
    verify(gateway).relateIssues("i9", "i1");
  }

  @Test
  void repairsPendingAttachmentInsteadOfCreatingSecondIssue() {
    // The one real duplicate risk: issueCreate succeeded, attachmentCreate failed, the activity
    // retried. Without this the retry finds no attachment and files a second ticket. The repair
    // must also post the occurrence comment — the audit trail records COMMENTED_EXISTING, and a
    // record claiming a comment that never happened is worse than the duplicate it prevents.
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    when(records.findById(fingerprint))
        .thenReturn(
            Optional.of(
                LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
                    .withPendingAttachment("i9", "SIM-9", "https://linear.app/i/9")));

    FiledIssue filed = filer().file(filing());

    verify(gateway).attachFingerprint(eq("i9"), anyString());
    verify(gateway).addComment(eq("i9"), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    // The known issue id on the record is the authority; a fresh lookup must never run, or a
    // legitimately-empty result (Linear indexing lag) would make the decider file a duplicate.
    verify(gateway, never()).issuesForFingerprint(anyString());
    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-9");
  }

  @Test
  void dryRunSkipsPendingAttachmentRepairWithoutWritingToLinear() {
    // dryRun must hold even on the repair path: an operator investigating a half-completed
    // filing with dryRun on must not trigger a live attachmentCreate/commentCreate.
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    LinearIssueRecord pending =
        LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
            .withPendingAttachment("i9", "SIM-9", "https://linear.app/i/9");
    when(records.findById(fingerprint)).thenReturn(Optional.of(pending));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    verifyNoInteractions(gateway);
    ArgumentCaptor<LinearIssueRecord> saved = ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(saved.capture());
    // The guard deliberately saves the untouched record, not a "repaired" copy: nothing was
    // actually attached, so the pending flag must still say so.
    assertThat(saved.getValue().attachmentPending()).isTrue();
  }

  @Test
  void replayedOccurrenceMutatesNothing() {
    // An activity retry after a fully successful run must not post a second "it happened again".
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    LinearIssueRecord already =
        LinearIssueRecord.first(fingerprint, "deploy", List.of("recreate", "backend"), NOW)
            .withPendingAttachment("i1", "SIM-1", "u")
            .withAttachmentWritten()
            .withDecision(
                new LinearIssueDecision(
                    NOW, FilingDecision.COMMENTED_EXISTING, "run-1", "deploy-prod", "x", false),
                NOW,
                IssueStateType.STARTED);
    when(records.findById(fingerprint)).thenReturn(Optional.of(already));

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-1");
    verifyNoInteractions(gateway);
    verify(records, never()).save(any(LinearIssueRecord.class));
  }

  @Test
  void dryRunReadsAndAuditsButMutatesNothingInLinear() {
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue filed = filer().file(filing());

    assertThat(filed.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(filed.issueIdentifier()).isNull();
    verify(gateway).issuesForFingerprint(anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).addComment(anyString(), anyString());
    verify(gateway, never()).attachFingerprint(anyString(), anyString());
    verify(gateway, never()).relateIssues(anyString(), anyString());
    verify(records).save(any(LinearIssueRecord.class));
  }

  @Test
  void realFilingAfterDryRunOfSameOccurrenceIsNotSuppressedByReplayGuard() {
    // The natural rollout gesture: dry run is switched off while a retry of the same
    // occurrence is still in backoff. The dry-run entry it left behind must not satisfy the
    // replay guard, or the retry would return the earlier hypothetical decision (a null
    // issue identifier) instead of actually filing.
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue dryRunResult = filer().file(filing());
    assertThat(dryRunResult.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(dryRunResult.issueIdentifier()).isNull();

    ArgumentCaptor<LinearIssueRecord> savedDuringDryRun =
        ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(savedDuringDryRun.capture());
    String fingerprint = Fingerprint.of("deploy", List.of("recreate", "backend"));
    when(records.findById(fingerprint)).thenReturn(Optional.of(savedDuringDryRun.getValue()));

    properties = new LinearProperties(true, "k", null, "SIM", null, false, null, null);
    when(gateway.createIssue(anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(new LinearGateway.CreatedIssue("i9", "SIM-9", "https://linear.app/i/9"));

    FiledIssue realResult = filer().file(filing());

    assertThat(realResult.decision()).isEqualTo(FilingDecision.FILED_NEW);
    assertThat(realResult.issueIdentifier()).isEqualTo("SIM-9");
    verify(gateway).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  void propagatesGatewayFaultsSoTemporalCanDecideWhetherToRetry() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer().file(filing()))
        .isInstanceOf(LinearApiException.class);
  }

  private static IssueFiling statusUpdate() {
    return new IssueFiling(
        "cvefix",
        List.of("repo", "current-vulnerabilities"),
        "Current vulnerabilities in repo",
        "body that must never be used to create an issue",
        "No current vulnerabilities as of scan run-9.",
        "run-9",
        "cve-scan-9",
        FilingMode.STATUS_UPDATE);
  }

  @Test
  void commentOnlyFilingCreatesNothingWhenNoIssueCarriesTheFingerprint() {
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue filed = filer().file(statusUpdate());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SKIPPED_NO_ISSUE);
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).addComment(anyString(), anyString());
  }

  @Test
  void commentOnlyFilingDoesNotFileRegressionAgainstCompletedIssue() {
    // "Everything is clean" must never file a regression ticket. Without this arm the decider's
    // FILED_REGRESSION outcome would create an issue whose body says there are no problems.
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));

    FiledIssue filed = filer().file(statusUpdate());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SKIPPED_NO_ISSUE);
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).relateIssues(anyString(), anyString());
  }

  @Test
  void commentOnlyFilingCommentsOnAnOpenIssueUsingTheProducersWordingVerbatim() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.STARTED)));

    FiledIssue filed = filer().file(statusUpdate());

    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    verify(gateway).addComment("i1", "No current vulnerabilities as of scan run-9.");
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  void commentOnlyFilingStaysQuietOnIssueHumanDeclined() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.CANCELED)));

    assertThat(filer().file(statusUpdate()).decision()).isEqualTo(FilingDecision.SUPPRESSED);
    verify(gateway, never()).addComment(anyString(), anyString());
  }

  @Test
  void ordinaryFilingStillPrefixesItsCommentWithSeenAgain() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.STARTED)));

    filer().file(filing());

    verify(gateway).addComment("i1", "Seen again: commit deadbeef at 12:00");
  }

  @Test
  void commentOnlyRegressionAgainstCompletedIssueReportsNoIssueEvenWhenTheRecordStillCarriesOne() {
    // Reachable on the ordinary path, not a hypothetical: this fingerprint was filed as SIM-1
    // long ago and SIM-1 was later completed. The stored record still carries SIM-1's id,
    // identifier and URL, and the commentOnly short-circuit never mutates it before returning
    // SKIPPED_NO_ISSUE — so `reported` must blank the issue fields on the way out, or a "the
    // repository is clean" filing would come back holding a real, resolved issue URL.
    String fingerprint = Fingerprint.of("cvefix", List.of("repo", "current-vulnerabilities"));
    LinearIssueRecord filedThenCompleted =
        LinearIssueRecord.first(
                fingerprint, "cvefix", List.of("repo", "current-vulnerabilities"), NOW)
            .withPendingAttachment("i1", "SIM-1", "https://linear.app/i/1")
            .withAttachmentWritten()
            .withDecision(
                new LinearIssueDecision(
                    NOW, FilingDecision.FILED_NEW, "run-0", "cve-scan-0", "x", false),
                NOW,
                IssueStateType.TRIAGE);
    when(records.findById(fingerprint)).thenReturn(Optional.of(filedThenCompleted));
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));

    FiledIssue filed = filer().file(statusUpdate());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SKIPPED_NO_ISSUE);
    assertThat(filed.issueIdentifier()).isNull();
    assertThat(filed.issueUrl()).isNull();
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).relateIssues(anyString(), anyString());

    // Only what is RETURNED is blanked. The audit trail keeps the whole history.
    ArgumentCaptor<LinearIssueRecord> saved = ArgumentCaptor.forClass(LinearIssueRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().issueIdentifier()).isEqualTo("SIM-1");
    assertThat(saved.getValue().issueUrl()).isEqualTo("https://linear.app/i/1");
  }

  @Test
  void commentOnlyDryRunReportsSkippedNoIssueInsteadOfFiledRegression() {
    // A dry run must be a faithful preview: a real run of this same commentOnly filing would
    // resolve to SKIPPED_NO_ISSUE (a completed issue carries the fingerprint), never to
    // FILED_REGRESSION, and the dry-run branch must agree rather than short-circuiting before
    // commentOnly is ever consulted.
    properties = new LinearProperties(true, "k", null, "SIM", null, true, null, null);
    when(gateway.issuesForFingerprint(anyString()))
        .thenReturn(List.of(issue(IssueStateType.COMPLETED)));

    FiledIssue filed = filer().file(statusUpdate());

    assertThat(filed.decision()).isEqualTo(FilingDecision.SKIPPED_NO_ISSUE);
    assertThat(filed.issueIdentifier()).isNull();
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    verify(gateway, never()).relateIssues(anyString(), anyString());
    verify(records).save(any(LinearIssueRecord.class));
  }
}
