package com.simonrowe.school.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.school.judge.PublicationJudge.Verdict;
import com.simonrowe.school.judge.SchoolJudgement.Outcome;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The summary page's counts, and how it tells that the owner reversed a decision. */
class SchoolJudgeControllerTest {

  private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");

  private final SchoolJudgementRepository judgements = mock(SchoolJudgementRepository.class);
  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolJudgeController controller = new SchoolJudgeController(
      mock(PublicationJudgeService.class), judgements, documents,
      new SchoolJudgeProperties(SchoolJudgeProperties.Mode.AUTO, null, 0, 0, List.of(), null,
          List.of(), List.of()),
      Clock.fixed(NOW, ZoneOffset.UTC));

  private static SchoolJudgement judgement(final String id, final Outcome outcome,
      final Instant at) {
    return new SchoolJudgement(id, SchoolSourceType.EMAIL, "Title " + id, at, "h",
        outcome == Outcome.APPROVED ? Verdict.PUBLIC : Verdict.PRIVATE, 0.95, "why", null, null,
        true, outcome, "m", PublicationJudge.VERSION, at);
  }

  private static SchoolDocument document(final String id, final Visibility visibility,
      final Instant declinedAt) {
    return new SchoolDocument(id, SchoolSourceType.EMAIL, id, "Title " + id,
        "Dear Parents.\nThe information contained within this e-mail is confidential.", NOW, NOW,
        visibility, null, null, null, null, false, List.of(), "h", declinedAt);
  }

  @Test
  void countsTheLast30DaysAndFlagsWhatTheOwnerReversed() {
    when(judgements.findTop200ByOrderByJudgedAtDesc()).thenReturn(List.of(
        judgement("kept", Outcome.APPROVED, NOW),
        judgement("revoked", Outcome.APPROVED, NOW),
        judgement("declined", Outcome.DECLINED, NOW),
        judgement("published-anyway", Outcome.DECLINED, NOW),
        judgement("waiting", Outcome.QUEUED, NOW),
        judgement("old", Outcome.APPROVED, NOW.minus(Duration.ofDays(40)))));
    when(documents.findAllById(any())).thenReturn(List.of(
        document("kept", Visibility.PUBLIC, null),
        document("revoked", Visibility.RESTRICTED, null),
        document("declined", Visibility.RESTRICTED, NOW),
        document("published-anyway", Visibility.PUBLIC, NOW),
        document("waiting", Visibility.RESTRICTED, null),
        document("old", Visibility.PUBLIC, null)));

    final SchoolJudgeController.Summary summary = controller.summary();

    assertThat(summary.mode()).isEqualTo("AUTO");
    assertThat(summary.approved()).isEqualTo(2);
    assertThat(summary.declined()).isEqualTo(2);
    assertThat(summary.queued()).isEqualTo(1);
    assertThat(summary.overridden()).isEqualTo(2);
    assertThat(summary.items()).hasSize(6);
    assertThat(summary.items())
        .filteredOn(SchoolJudgeController.Item::overridden)
        .extracting(SchoolJudgeController.Item::id)
        .containsExactlyInAnyOrder("revoked", "published-anyway");
    assertThat(summary.items().get(0).preview()).isEqualTo("Dear Parents.");
  }

  @Test
  void deletedDocumentStillShowsItsJudgementButIsNotCountedAsReversed() {
    when(judgements.findTop200ByOrderByJudgedAtDesc())
        .thenReturn(List.of(judgement("gone", Outcome.APPROVED, NOW)));
    when(documents.findAllById(any())).thenReturn(List.of());

    final SchoolJudgeController.Item item = controller.summary().items().get(0);

    assertThat(item.currentState()).isEqualTo("MISSING");
    assertThat(item.title()).isEqualTo("Title gone");
    assertThat(item.overridden()).isFalse();
  }
}
