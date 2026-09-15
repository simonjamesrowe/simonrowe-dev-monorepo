package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * "Was there a newsletter last week" is a query, not a similarity search.
 *
 * <p>It was being answered by one, and the failure was quiet and complete: a dozen weekly
 * newsletters sit in almost the same place in vector space, so top-k returns one of them roughly
 * at random and nothing in the path carries a date. Production answered a question about the
 * week just gone from a newsletter dated 10 July — and volunteered that the 10 July one was the
 * most recent it had.
 *
 * <p>These tests pin the three properties that make the replacement trustworthy: the window is
 * really the window, the tier filter is really applied, and the calendar container document can
 * never appear.
 */
class SchoolCommunicationsQueryTest {

  private static final ZoneId ZONE = ZoneId.of("Europe/London");

  private SchoolDocumentRepository documents;
  private SchoolQueryService queries;

  @BeforeEach
  void setUp() {
    documents = mock(SchoolDocumentRepository.class);
    queries = new SchoolQueryService(
        mock(SchoolEventRepository.class), documents,
        Clock.fixed(Instant.parse("2026-09-14T07:00:00Z"), ZONE));
    when(documents.findPublishedBetween(anyList(), anyList(), any(), any()))
        .thenReturn(List.of());
  }

  @Test
  @DisplayName("the window includes everything sent on its last day, not just up to midnight")
  void theLastDayIsWhole() {
    queries.communicationsBetween(
        LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), SchoolAudience.anonymous());

    // The newsletter that started all this was sent at 15:03 on the closing day of the window
    // somebody would ask about. A window ending at the START of its last day loses exactly the
    // most recent item, which is the one nearly every question about a period is really about.
    assertThat(instantArgument(3))
        .isAfterOrEqualTo(Instant.parse("2026-09-11T15:03:59Z"))
        .isBefore(LocalDate.of(2026, 9, 12).atStartOfDay(ZONE).toInstant());
    assertThat(instantArgument(2))
        .isEqualTo(LocalDate.of(2026, 9, 7).atStartOfDay(ZONE).toInstant());
  }

  @Test
  @DisplayName("the calendar feed's container document can never be a communication")
  void theCalendarContainerIsExcluded() {
    queries.communicationsBetween(
        LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), SchoolAudience.anonymous());

    // SchoolIngestService.ingestCalendar re-stamps that document with Instant.now() on every
    // pass — every thirty minutes in production. Included, it would be the newest thing the
    // school had "published" in every window for ever, and "what did the school send this week"
    // would always lead with a stub reading "The school's published calendar feed."
    assertThat(sourceTypeArgument())
        .doesNotContain(SchoolSourceType.CALENDAR_FEED)
        .containsExactlyInAnyOrder(
            SchoolSourceType.EMAIL, SchoolSourceType.WEBSITE_PAGE, SchoolSourceType.PDF);
  }

  @Test
  @DisplayName("another school's page is not something this school published")
  void otherSchoolsContentIsExcluded() {
    queries.communicationsBetween(
        LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), SchoolAudience.anonymous());

    // A note pasted into the admin console is a parents'-group message somebody transcribed,
    // and an external page is a secondary school's own website read from a link in one. Both
    // legitimately answer "when is the Kingsdale open evening"; neither answers "what did the
    // school send last week", and including them attributes another school's announcement to
    // this one. This is why EXTERNAL_PAGE exists as a type separate from WEBSITE_PAGE at all.
    assertThat(sourceTypeArgument())
        .doesNotContain(SchoolSourceType.PASTED_NOTE, SchoolSourceType.EXTERNAL_PAGE);
  }

  @Test
  @DisplayName("an anonymous visitor's window is filtered to the public tier")
  void tierIsApplied() {
    queries.communicationsBetween(
        LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), SchoolAudience.anonymous());

    assertThat(visibilityArgument()).containsExactly(Visibility.PUBLIC);
  }

  @Test
  @DisplayName("an over-wide window is clamped from the recent end, not the old one")
  void anOverWideWindowKeepsTheRecentHalf() {
    queries.communicationsBetween(
        LocalDate.of(2025, 9, 1), LocalDate.of(2026, 9, 13), SchoolAudience.anonymous());

    // Clamped rather than refused: the caller is a model turning "this year" into two dates,
    // and the recent end of an over-wide window is the half being asked about.
    assertThat(instantArgument(2))
        .isEqualTo(LocalDate.of(2026, 9, 13).minusDays(62).atStartOfDay(ZONE).toInstant());
  }

  @Test
  @DisplayName("a backwards window asks the database nothing")
  void backwardsWindowIsEmpty() {
    final List<SchoolDocument> found = queries.communicationsBetween(
        LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 7), SchoolAudience.anonymous());

    assertThat(found).isEmpty();
    verifyNoInteractions(documents);
  }

  @SuppressWarnings("unchecked")
  private List<Visibility> visibilityArgument() {
    final ArgumentCaptor<List<Visibility>> captor = ArgumentCaptor.forClass(List.class);
    verify(documents).findPublishedBetween(captor.capture(), anyList(), any(), any());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<SchoolSourceType> sourceTypeArgument() {
    final ArgumentCaptor<List<SchoolSourceType>> captor = ArgumentCaptor.forClass(List.class);
    verify(documents).findPublishedBetween(anyList(), captor.capture(), any(), any());
    return captor.getValue();
  }

  private Instant instantArgument(final int position) {
    final ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    final ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(documents).findPublishedBetween(anyList(), anyList(), from.capture(), to.capture());
    return position == 2 ? from.getValue() : to.getValue();
  }
}
