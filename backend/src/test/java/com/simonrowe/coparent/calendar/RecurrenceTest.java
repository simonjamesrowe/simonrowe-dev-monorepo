package com.simonrowe.coparent.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecurrenceTest {

  @ParameterizedTest
  @ValueSource(strings = {"tuesday", "Tuesday", "TUE", "tu", " Tues "})
  void resolvesEverySpellingOfWeekday(final String spelling) {
    assertThat(Recurrence.canonicalDay(spelling)).contains("tuesday");
  }

  @Test
  void distinguishesWeekdaysSharingAnInitial() {
    assertThat(Recurrence.canonicalDay("TH")).contains("thursday");
    assertThat(Recurrence.canonicalDay("SU")).contains("sunday");
    assertThat(Recurrence.canonicalDay("sa")).contains("saturday");
  }

  @ParameterizedTest
  @ValueSource(strings = {"t", "s", "tuxedo", "someday", "", " "})
  void rejectsAnythingThatIsNotWeekday(final String spelling) {
    assertThat(Recurrence.canonicalDay(spelling)).isEmpty();
  }

  @Test
  void normalisesFrequencyCase() {
    assertThat(Recurrence.canonicalFrequency("WEEKLY")).contains("weekly");
    assertThat(Recurrence.canonicalFrequency("Daily")).contains("daily");
    assertThat(Recurrence.canonicalFrequency("monthly")).isEmpty();
    assertThat(Recurrence.canonicalFrequency(null)).isEmpty();
  }
}
