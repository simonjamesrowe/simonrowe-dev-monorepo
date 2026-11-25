package com.simonrowe.school.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.school.admin.SchoolApprovalService;
import com.simonrowe.school.ingest.GmailClient;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.judge.PublicationJudge.Answer;
import com.simonrowe.school.judge.PublicationJudge.Verdict;
import com.simonrowe.school.judge.SchoolJudgement.Outcome;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sweep: which documents it judges, what it does with each verdict, and that every decision
 * goes through the approval service rather than around it.
 */
class PublicationJudgeServiceTest {

  private static final String BROADCAST = "info@school.example";
  private static final Instant THIS_TERM = Instant.parse("2026-11-20T15:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-24T09:00:00Z"), ZoneOffset.UTC);
  private static final String VERIFIED = "mx.google.com; spf=pass (ok) "
      .concat("smtp.mailfrom=bounces+189724-a@sendgrid.net; dmarc=fail header.from=school.example");

  private final Map<String, SchoolDocument> docs = new LinkedHashMap<>();
  private final Map<String, SchoolJudgement> stored = new HashMap<>();
  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolJudgementRepository judgements = mock(SchoolJudgementRepository.class);
  private final SchoolApprovalService approvals = mock(SchoolApprovalService.class);
  private final PublicationJudge judge = mock(PublicationJudge.class);
  private final GmailClient gmail = mock(GmailClient.class);
  private final Map<String, String> authHeader = new HashMap<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void wire() throws Exception {
    when(documents.findAwaitingApproval()).thenAnswer(inv -> docs.values().stream()
        .filter(SchoolDocument::needsDecision).toList());
    when(documents.findById(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(docs.get(inv.<String>getArgument(0))));
    when(judgements.findAllById(any())).thenAnswer(inv -> {
      final List<SchoolJudgement> found = new ArrayList<>();
      for (String id : (Iterable<String>) inv.getArgument(0)) {
        if (stored.containsKey(id)) {
          found.add(stored.get(id));
        }
      }
      return found;
    });
    when(judgements.findById(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<String>getArgument(0))));
    when(judgements.save(any())).thenAnswer(inv -> {
      final SchoolJudgement judgement = inv.getArgument(0);
      stored.put(judgement.id(), judgement);
      return judgement;
    });
    doAnswer(inv -> {
      final String id = inv.getArgument(0);
      docs.put(id, docs.get(id).withApproval(inv.getArgument(1), Instant.now(), true));
      return Optional.of(docs.get(id));
    }).when(approvals).approve(anyString(), anyString());
    doAnswer(inv -> {
      final String id = inv.getArgument(0);
      docs.put(id, docs.get(id).withDeclined(Instant.now()));
      return Optional.of(docs.get(id));
    }).when(approvals).decline(anyString());
    when(gmail.isConfigured()).thenReturn(true);
    when(gmail.fetchSenderHeaders(anyString())).thenAnswer(inv -> {
      final String id = inv.getArgument(0);
      return Optional.of(List.of(
          Map.entry("From", "Example School <" + BROADCAST + ">"),
          Map.entry("Authentication-Results", authHeader.getOrDefault(id, VERIFIED))));
    });
  }

  private PublicationJudgeService service(final SchoolJudgeProperties.Mode mode) {
    return new PublicationJudgeService(
        new SchoolJudgeProperties(mode, "test-model", 0, 0, List.of(BROADCAST), "school.example",
            List.of("bounces+189724-*@sendgrid.net"), List.of()),
        documents, judgements, approvals, judge, gmail, CLOCK);
  }

  private static SchoolDocument email(final String messageId, final String subject) {
    return new SchoolDocument(SchoolIds.documentId(SchoolSourceType.EMAIL, messageId),
        SchoolSourceType.EMAIL, messageId, subject, "Dear Parents and Carers, news.", THIS_TERM,
        THIS_TERM, Visibility.RESTRICTED, null, null, null, null, false, List.of(), "h-" + subject,
        null);
  }

  private static SchoolDocument attachment(final String messageId, final String filename) {
    final String ref = "gmail:" + messageId + ":" + filename;
    return new SchoolDocument(SchoolIds.documentId(SchoolSourceType.PDF, ref),
        SchoolSourceType.PDF, ref, filename, "Dear Parents, a letter.", THIS_TERM, THIS_TERM,
        Visibility.RESTRICTED, null, null, null, null, false, List.of(), "h-" + filename, null);
  }

  private SchoolDocument add(final SchoolDocument document) {
    docs.put(document.id(), document);
    return document;
  }

  private void judgeSays(final Verdict verdict, final double confidence) {
    when(judge.judge(anyString(), anyString(), anyString()))
        .thenReturn(new Answer(verdict, confidence, "because"));
  }

  @Test
  void offDoesNothingAtAll() {
    add(email("m1", "Newsletter"));

    assertThat(service(SchoolJudgeProperties.Mode.OFF).sweep().ran()).isFalse();
    verifyNoInteractions(judge, approvals, gmail);
  }

  @Test
  void confidentPublicVerdictFromVerifiedSenderPublishesThroughApprovalService() {
    final SchoolDocument newsletter = add(email("m1", "Newsletter"));
    judgeSays(Verdict.PUBLIC, 0.97);

    final PublicationJudgeService.SweepResult result =
        service(SchoolJudgeProperties.Mode.AUTO).sweep();

    assertThat(result.approved()).isEqualTo(1);
    verify(approvals).approve(newsletter.id(), PublicationJudgeService.APPROVER);
    assertThat(stored.get(newsletter.id()).outcome()).isEqualTo(Outcome.APPROVED);
    assertThat(stored.get(newsletter.id()).senderAuthenticated()).isTrue();
  }

  @Test
  void anUnverifiedSenderIsNeverPublishedHoweverConfidentTheJudge() {
    final SchoolDocument forged = add(email("m1", "Newsletter"));
    authHeader.put("m1", "mx.google.com; spf=pass smtp.mailfrom=bounces+1-a@sendgrid.net");
    judgeSays(Verdict.PUBLIC, 0.99);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals, never()).approve(anyString(), anyString());
    assertThat(stored.get(forged.id()).outcome()).isEqualTo(Outcome.QUEUED);
  }

  @Test
  void confidentPrivateVerdictDeclines() {
    final SchoolDocument chase = add(email("m1", "Trip balance"));
    judgeSays(Verdict.PRIVATE, 0.97);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).decline(chase.id());
  }

  @Test
  void unsureAndBelowThresholdVerdictsAreLeftForPerson() {
    add(email("m1", "Hmm"));
    add(email("m2", "Maybe"));
    when(judge.judge(anyString(), anyString(), anyString()))
        .thenReturn(new Answer(Verdict.UNSURE, 0.99, "could be either"))
        .thenReturn(new Answer(Verdict.PUBLIC, 0.89, "probably"));

    final PublicationJudgeService.SweepResult result =
        service(SchoolJudgeProperties.Mode.AUTO).sweep();

    assertThat(result.queued()).isEqualTo(2);
    verifyNoInteractions(approvals);
  }

  @Test
  void ruleDeclinesWithoutAskingTheModel() {
    final SchoolDocument reply = add(email("m1", "RE: collection today"));

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).decline(reply.id());
    verifyNoInteractions(judge);
    assertThat(stored.get(reply.id()).rule()).isEqualTo("reply");
  }

  @Test
  void shadowModeRecordsVerdictAndDecidesNothing() {
    final SchoolDocument newsletter = add(email("m1", "Newsletter"));
    judgeSays(Verdict.PUBLIC, 0.99);

    service(SchoolJudgeProperties.Mode.SHADOW).sweep();

    verifyNoInteractions(approvals);
    assertThat(stored.get(newsletter.id()).outcome()).isEqualTo(Outcome.SHADOW);
  }

  @Test
  void switchingFromShadowToAutoDecidesWhatShadowOnlyRecorded() {
    final SchoolDocument newsletter = add(email("m1", "Newsletter"));
    judgeSays(Verdict.PUBLIC, 0.99);
    service(SchoolJudgeProperties.Mode.SHADOW).sweep();

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).approve(newsletter.id(), PublicationJudgeService.APPROVER);
  }

  @Test
  void unreadableSenderHeadersAreSkippedNotDeclined() throws Exception {
    final SchoolDocument newsletter = add(email("m1", "Newsletter"));
    when(gmail.fetchSenderHeaders("m1")).thenReturn(Optional.empty());

    final PublicationJudgeService.SweepResult result =
        service(SchoolJudgeProperties.Mode.AUTO).sweep();

    assertThat(result.skipped()).isEqualTo(1);
    assertThat(stored).doesNotContainKey(newsletter.id());
    verifyNoInteractions(approvals);
  }

  @Test
  void decisionThePersonReversedIsNotJudgedAgain() {
    final SchoolDocument newsletter = add(email("m1", "Newsletter"));
    judgeSays(Verdict.PUBLIC, 0.99);
    final PublicationJudgeService auto = service(SchoolJudgeProperties.Mode.AUTO);
    auto.sweep();
    // The owner revokes it: back to restricted and undecided, so it is in the queue again.
    docs.put(newsletter.id(), docs.get(newsletter.id()).withApprovalRevoked());

    auto.sweep();

    verify(approvals).approve(anyString(), anyString());
    assertThat(docs.get(newsletter.id()).needsDecision()).isTrue();
  }

  @Test
  void emailLeftForPersonIsNotAskedAgainEverySweep() {
    add(email("m1", "Hmm"));
    judgeSays(Verdict.UNSURE, 0.5);
    final PublicationJudgeService auto = service(SchoolJudgeProperties.Mode.AUTO);

    auto.sweep();
    auto.sweep();

    verify(judge, times(1)).judge(anyString(), anyString(), anyString());
  }

  @Test
  void emailLeftForPersonIsAskedAgainWhenThePromptChanges() {
    final SchoolDocument marginal = add(email("m1", "Hmm"));
    stored.put(marginal.id(), new SchoolJudgement(marginal.id(), SchoolSourceType.EMAIL,
        "Hmm", THIS_TERM, marginal.contentHash(), Verdict.UNSURE, 0.5, "older prompt", null,
        null, true, Outcome.QUEUED, "m", "judge-v0", THIS_TERM));
    judgeSays(Verdict.PUBLIC, 0.95);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).approve(marginal.id(), PublicationJudgeService.APPROVER);
  }

  @Test
  void changedTextIsJudgedAgain() {
    final SchoolDocument original = add(email("m1", "Newsletter"));
    judgeSays(Verdict.UNSURE, 0.5);
    final PublicationJudgeService auto = service(SchoolJudgeProperties.Mode.AUTO);
    auto.sweep();
    docs.put(original.id(), new SchoolDocument(original.id(), original.sourceType(),
        original.sourceRef(), original.title(), "corrected", original.publishedAt(),
        original.ingestedAt(), Visibility.RESTRICTED, null, null, null, null, false, List.of(),
        "new-hash", null));
    judgeSays(Verdict.PUBLIC, 0.99);

    auto.sweep();

    verify(approvals).approve(original.id(), PublicationJudgeService.APPROVER);
  }

  @Test
  void anAttachmentFollowsItsEmailWhenTheEmailIsPublished() {
    add(email("m1", "Winter performance"));
    final SchoolDocument letter = add(attachment("m1", "Winter Performances Letter.pdf"));
    judgeSays(Verdict.PUBLIC, 0.99);

    final PublicationJudgeService.SweepResult result =
        service(SchoolJudgeProperties.Mode.AUTO).sweep();

    assertThat(result.approved()).isEqualTo(2);
    verify(approvals).approve(letter.id(), PublicationJudgeService.APPROVER);
    assertThat(stored.get(letter.id()).rule()).isEqualTo("follows-email");
  }

  @Test
  void anAttachmentFollowsItsEmailWhenTheEmailIsKeptPrivate() {
    add(email("m1", "Wrap balance"));
    final SchoolDocument statement = add(attachment("m1", "Statement.pdf"));
    judgeSays(Verdict.PRIVATE, 0.99);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).decline(statement.id());
  }

  @Test
  void pupilsReportStaysPrivateEvenWhenItsEmailIsPublished() {
    add(email("m1", "Reports"));
    final SchoolDocument report = add(attachment("m1", "Smith, Sam .pdf"));
    judgeSays(Verdict.PUBLIC, 0.99);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).decline(report.id());
  }

  @Test
  void attachmentWaitsWhileItsEmailIsLeftForPerson() {
    add(email("m1", "Hmm"));
    final SchoolDocument letter = add(attachment("m1", "Letter.pdf"));
    judgeSays(Verdict.UNSURE, 0.4);

    final PublicationJudgeService.SweepResult result =
        service(SchoolJudgeProperties.Mode.AUTO).sweep();

    assertThat(result.skipped()).isEqualTo(1);
    assertThat(stored).doesNotContainKey(letter.id());
  }

  @Test
  void anAttachmentFollowsAnEmailTheOwnerDecided() {
    final SchoolDocument decided = email("m1", "Newsletter")
        .withApproval("owner", THIS_TERM, true);
    add(decided);
    final SchoolDocument letter = add(attachment("m1", "Newsletter.pdf"));

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verify(approvals).approve(letter.id(), PublicationJudgeService.APPROVER);
    verifyNoInteractions(judge);
  }

  @Test
  void thresholdsAreInclusive() {
    final PublicationJudgeService auto = service(SchoolJudgeProperties.Mode.AUTO);

    assertThat(auto.outcome(Verdict.PUBLIC, 0.9, false, true)).isEqualTo(Outcome.APPROVED);
    assertThat(auto.outcome(Verdict.PUBLIC, 0.8999, false, true)).isEqualTo(Outcome.QUEUED);
    assertThat(auto.outcome(Verdict.PRIVATE, 0.9, false, false)).isEqualTo(Outcome.DECLINED);
    assertThat(auto.outcome(Verdict.PRIVATE, 0.5, false, false)).isEqualTo(Outcome.QUEUED);
    assertThat(auto.outcome(Verdict.PRIVATE, 1, true, false)).isEqualTo(Outcome.DECLINED);
    assertThat(auto.outcome(Verdict.UNSURE, 1, false, true)).isEqualTo(Outcome.QUEUED);
  }

  @Test
  void documentsThatAreNotSchoolMailAreLeftAlone() {
    final SchoolDocument page = new SchoolDocument("p1", SchoolSourceType.EXTERNAL_PAGE,
        "https://example.com", "Page", "text", THIS_TERM, THIS_TERM, Visibility.RESTRICTED,
        null, null, null, null, false, List.of(), "h", null);
    add(page);

    service(SchoolJudgeProperties.Mode.AUTO).sweep();

    verifyNoInteractions(judge, approvals);
    assertThat(stored).isEmpty();
  }
}
