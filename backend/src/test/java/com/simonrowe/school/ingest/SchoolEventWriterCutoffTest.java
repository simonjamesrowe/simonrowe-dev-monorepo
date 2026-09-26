package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the ingest cutoff at the event writer.
 *
 * <p>The cutoff started life inside the Gmail query alone, which capped email and left the
 * calendar feed's independent six-month lookback backfilling the previous academic year. These
 * tests exist so that stays fixed for every producer, not just for the one that was noticed
 * first.
 */
class SchoolEventWriterCutoffTest {

  private static final LocalDate CUTOFF = LocalDate.of(2026, 7, 1);

  private SchoolEventRepository repository;
  private SchoolEventWriter writer;

  @BeforeEach
  void setUp() {
    repository = mock(SchoolEventRepository.class);
    when(repository.findById(any())).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    writer = new SchoolEventWriter(repository, propertiesWithCutoff(CUTOFF));
  }

  @Test
  void dropsAnEventEndingBeforeTheCutoff() {
    writer.write(event(LocalDate.of(2026, 3, 11), null));

    verify(repository, never()).save(any());
  }

  @Test
  void keepsAnEventOnTheCutoff() {
    writer.write(event(CUTOFF, null));

    verify(repository).save(any());
  }

  @Test
  void keepsAnEventSpanningTheCutoff() {
    writer.write(event(LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 4)));

    verify(repository).save(any());
  }

  @Test
  void keepsEverythingWhenNoCutoffIsConfigured() {
    final SchoolEventWriter unbounded =
        new SchoolEventWriter(repository, propertiesWithCutoff(null));

    unbounded.write(event(LocalDate.of(2020, 1, 1), null));

    verify(repository).save(any());
  }

  @Test
  void reportsWhyAnEventWasDropped() {
    assertThat(writer.isBeforeCutoff(event(LocalDate.of(2026, 3, 11), null))).isTrue();
    assertThat(writer.isBeforeCutoff(event(LocalDate.of(2026, 9, 1), null))).isFalse();
  }

  private static SchoolEvent event(final LocalDate start, final LocalDate end) {
    return new SchoolEvent(
        "id-" + start,
        "Y3 Class School Trip",
        start,
        end,
        true,
        SchoolEvent.EventType.TRIP,
        List.of("Year 3"),
        "2025-26",
        SchoolSourceType.CALENDAR_FEED,
        "doc-1",
        Visibility.PUBLIC,
        null,
        null,
        null,
        null);
  }

  private static SchoolProperties propertiesWithCutoff(final LocalDate cutoff) {
    return new SchoolProperties(
        true, cutoff, List.of(), List.of(), null, null, null, 0, null, null, null, 1000L,
        null, null);
  }
}
