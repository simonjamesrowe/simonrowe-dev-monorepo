package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventTimesTest {

  @ParameterizedTest(name = "\"{0}\" starts {1} minutes after midnight")
  @DisplayName("reads the time formats the calendar feed and letters use")
  @CsvSource(delimiter = '|', value = {
      "6:00pm|1080",
      "9.15am|555",
      "9am|540",
      "12pm|720",
      "12:30am|30",
      "3:30 - 4:30pm|930",
      "11:30 - 1pm|690",
      "9:00-10:15am|540",
      "18:00|1080",
      "8.45 a.m.|525",
      "Doors open 6PM|1080",
  })
  void readsTimes(final String time, final int minutes) {
    assertThat(EventTimes.minutesOf(time, false)).isEqualTo(minutes);
  }

  @Test
  @DisplayName("a bare number is not a time, so 'Year 3' cannot put an event at 3am")
  void bareNumberIsNotTime() {
    assertThat(EventTimes.minutesOf("Year 3 only", false)).isEqualTo(EventTimes.UNKNOWN);
    assertThat(EventTimes.minutesOf("Year 3 only, 2:15pm", false)).isEqualTo(855);
  }

  @Test
  @DisplayName("an all-day event sorts first and an unreadable time sorts last")
  void allDayAndUnknown() {
    assertThat(EventTimes.minutesOf("All Day", true)).isEqualTo(EventTimes.ALL_DAY);
    assertThat(EventTimes.minutesOf(null, true)).isEqualTo(EventTimes.ALL_DAY);
    assertThat(EventTimes.minutesOf("after lunch", false)).isEqualTo(EventTimes.UNKNOWN);
    assertThat(EventTimes.minutesOf(null, false)).isEqualTo(EventTimes.UNKNOWN);
  }

  @Test
  @DisplayName("out-of-range values are rejected rather than wrapped")
  void outOfRange() {
    assertThat(EventTimes.minutesOf("13pm", false)).isEqualTo(EventTimes.UNKNOWN);
    assertThat(EventTimes.minutesOf("25:00", false)).isEqualTo(EventTimes.UNKNOWN);
    assertThat(EventTimes.minutesOf("9:75am", false)).isEqualTo(EventTimes.UNKNOWN);
  }

  @Test
  @DisplayName("a long, number-heavy input is scanned in bounded time")
  void boundedOnLongInput() {
    // The field comes from a model reading a letter. A worst-case shape for the pattern —
    // digits, separators and spaces with no am/pm to end on — must not make it backtrack.
    final String hostile = "1:1 ".repeat(30_000) + "x";
    final long started = System.nanoTime();
    assertThat(EventTimes.minutesOf(hostile, false)).isEqualTo(EventTimes.UNKNOWN);
    assertThat(System.nanoTime() - started).isLessThan(1_000_000_000L);
  }
}
