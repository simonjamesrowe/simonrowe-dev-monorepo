package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.Child;
import java.time.Instant;
import java.time.LocalDate;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChildSchoolYearTest {

  @ParameterizedTest
  @CsvSource(nullValues = "-", value = {
      // Born in the 2018/19 school year: Year 3 in 2026/27.
      "2018-09-01,2026-11-20,Year 3",
      "2019-08-31,2026-11-20,Year 3",
      "2019-09-01,2026-11-20,Year 2",
      // Moves up on 1 September with nobody editing anything.
      "2019-03-10,2027-08-31,Year 3",
      "2019-03-10,2027-09-01,Year 4",
      "2016-02-01,2026-11-20,Year 6",
      "2016-02-01,2027-09-01,-",
      "2022-03-01,2026-11-20,Reception",
      "2023-03-01,2026-11-20,-",
  })
  void worksOutTheYearGroupFromTheDateOfBirth(final LocalDate born, final LocalDate on,
      final String expected) {
    assertThat(ChildSchoolYear.yearGroupFor(born, on).orElse(null)).isEqualTo(expected);
  }

  @Test
  void explicitYearGroupWins() {
    final Child child = child(new Child.SchoolCalendarLink("Omar", "Year 2", "2026/27"));
    assertThat(ChildSchoolYear.yearGroupOf(child, LocalDate.of(2026, 11, 20)))
        .contains("Year 2");
  }

  @Test
  void classFromLastSchoolYearIsNotTrusted() {
    final Child child = child(new Child.SchoolCalendarLink("Omar", null, "2026/27"));
    assertThat(ChildSchoolYear.classOf(child, LocalDate.of(2027, 7, 1))).contains("Omar");
    assertThat(ChildSchoolYear.classOf(child, LocalDate.of(2027, 9, 1))).isEmpty();
  }

  @Test
  void unlinkedChildHasNoYear() {
    assertThat(ChildSchoolYear.yearGroupOf(child(null), LocalDate.of(2026, 11, 20))).isEmpty();
  }

  private static Child child(final Child.SchoolCalendarLink link) {
    return new Child(new ObjectId(), new ObjectId(), "Robin Example",
        LocalDate.of(2019, 3, 10), null, null, null, null, Instant.now(), Instant.now(), link);
  }
}
