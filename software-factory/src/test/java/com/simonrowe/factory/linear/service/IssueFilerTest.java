package com.simonrowe.factory.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
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
    when(gateway.issuesForFingerprint(anyString())).thenReturn(List.of());

    FiledIssue filed = filer().file(filing());

    verify(gateway).attachFingerprint(eq("i9"), anyString());
    verify(gateway).addComment(eq("i9"), anyString());
    verify(gateway, never()).createIssue(anyString(), anyString(), anyInt(), anyString());
    assertThat(filed.decision()).isEqualTo(FilingDecision.COMMENTED_EXISTING);
    assertThat(filed.issueIdentifier()).isEqualTo("SIM-9");
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
                new com.simonrowe.factory.linear.persistence.LinearIssueDecision(
                    NOW, FilingDecision.COMMENTED_EXISTING, "run-1", "deploy-prod", "x"),
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
    verify(records).save(any(LinearIssueRecord.class));
  }

  @Test
  void propagatesGatewayFaultsSoTemporalCanDecideWhetherToRetry() {
    when(gateway.issuesForFingerprint(anyString()))
        .thenThrow(new LinearApiException("Linear returned 503", true));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> filer().file(filing()))
        .isInstanceOf(LinearApiException.class);
  }
}
