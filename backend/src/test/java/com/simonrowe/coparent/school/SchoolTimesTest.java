package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchoolTimesTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', nullValues = "-", value = {
      "09.15 to 10.45am|09:15|10:45",
      "2:00pm – 5:00pm|14:00|17:00",
      "3 PM|15:00|-",
      "09:30|09:30|-",
      "8:30am|08:30|-",
      "6:00pm|18:00|-",
      "3:30 - 4:30pm|15:30|16:30",
      "11:30 - 1pm|11:30|13:00",
      "9 - 10am|09:00|10:00",
      "2.30 PM|14:30|-",
  })
  void readsTheWaysTheSchoolWritesTimes(final String text, final String start,
      final String end) {
    final SchoolTimes.Range range = SchoolTimes.parse(text).orElseThrow();
    assertThat(SchoolTimes.format(range.start())).isEqualTo(start);
    assertThat(range.end() == null ? null : SchoolTimes.format(range.end())).isEqualTo(end);
  }

  @ParameterizedTest
  @CsvSource({"All Day", "Year 3 only", "''"})
  void findsNoTimeWhereThereIsNone(final String text) {
    assertThat(SchoolTimes.parse(text)).isEmpty();
  }

  @Test
  void hourOnItsOwnBeforeEightIsTheAfternoon() {
    assertThat(SchoolTimes.parse("3:15").orElseThrow().start()).isEqualTo(LocalTime.of(15, 15));
  }

  @Test
  void checksTheCalendarsOwnFormat() {
    assertThat(SchoolTimes.isClockTime("09:15")).isTrue();
    assertThat(SchoolTimes.isClockTime("9:15")).isFalse();
    assertThat(SchoolTimes.isClockTime("24:00")).isFalse();
    assertThat(SchoolTimes.isClockTime(null)).isFalse();
  }

  @Test
  void hugeInputIsReadQuickly() {
    assertTimeoutPreemptively(Duration.ofSeconds(2),
        () -> SchoolTimes.parse("1".repeat(100_000) + ":"));
  }
}
