package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.DueKind;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Which reminder a document needs on a given day, by date alone. */
class ReminderWindowsTest {

  private static final LocalDate DUE = LocalDate.of(2027, 8, 2);

  @ParameterizedTest(name = "{0} on {1}: {2}")
  @CsvSource(nullValues = "none", value = {
      // Expiry: six months, then three months.
      "EXPIRES, 2027-02-01, none",
      "EXPIRES, 2027-02-02, 6m",
      "EXPIRES, 2027-04-30, 6m",
      "EXPIRES, 2027-05-02, 3m",
      "EXPIRES, 2027-08-02, 3m",
      // Past the date: nothing.
      "EXPIRES, 2027-08-03, none",
      // Review: one month, then one week.
      "REVIEW, 2027-07-01, none",
      "REVIEW, 2027-07-02, 1m",
      "REVIEW, 2027-07-25, 1m",
      "REVIEW, 2027-07-26, 1w",
  })
  void opensEachThresholdOnItsDay(final DueKind kind, final LocalDate today,
      final String expected) {
    assertThat(ReminderWindows.due(today, DUE, kind, Set.of())
        .map(due -> due.threshold().label()).orElse(null))
        .isEqualTo(expected);
  }

  @Test
  void reminderAlreadySentIsNotSentAgain() {
    final LocalDate today = LocalDate.of(2027, 3, 1);

    assertThat(ReminderWindows.due(today, DUE, DueKind.EXPIRES, Set.of("2027-08-02:6m")))
        .isEmpty();
  }

  @Test
  void whenSeveralAreOpenOneEmailGoesForTheNearestAndAllAreMarked() {
    final LocalDate today = LocalDate.of(2027, 6, 1);

    final ReminderWindows.Due due = ReminderWindows.due(today, DUE, DueKind.EXPIRES, Set.of())
        .orElseThrow();

    assertThat(due.threshold().label()).isEqualTo("3m");
    assertThat(due.keys()).containsExactly("2027-08-02:6m", "2027-08-02:3m");
    assertThat(due.key()).isEqualTo("2027-08-02:3m");
  }

  @Test
  void theNearerThresholdStillGoesAfterTheFartherWasSent() {
    final LocalDate today = LocalDate.of(2027, 5, 10);

    assertThat(ReminderWindows.due(today, DUE, DueKind.EXPIRES, Set.of("2027-08-02:6m"))
        .map(due -> due.threshold().label())).contains("3m");
  }

  @Test
  void renewalWithNewDateStartsAfresh() {
    final LocalDate renewed = LocalDate.of(2037, 8, 2);
    final Set<String> sentForOldDate = Set.of("2027-08-02:6m", "2027-08-02:3m");

    assertThat(ReminderWindows.due(LocalDate.of(2037, 3, 1), renewed, DueKind.EXPIRES,
        sentForOldDate).map(due -> due.key())).contains("2037-08-02:6m");
    assertThat(ReminderWindows.due(LocalDate.of(2027, 6, 1), renewed, DueKind.EXPIRES,
        sentForOldDate)).isEmpty();
  }

  @Test
  void noDateOrKindMeansNoReminder() {
    final LocalDate today = LocalDate.of(2027, 7, 30);
    assertThat(ReminderWindows.due(today, null, DueKind.REVIEW, Set.of())).isEmpty();
    assertThat(ReminderWindows.due(today, DUE, null, Set.of())).isEmpty();
  }
}
