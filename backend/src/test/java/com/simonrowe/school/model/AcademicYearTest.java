package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AcademicYearTest {

  @Test
  @DisplayName("September starts a new academic year")
  void septemberStartsTheNextYear() {
    assertThat(AcademicYear.of(LocalDate.of(2026, 9, 1))).isEqualTo("2026/27");
    assertThat(AcademicYear.of(LocalDate.of(2026, 12, 31))).isEqualTo("2026/27");
  }

  @Test
  @DisplayName("January to August belong to the year that started the previous September")
  void springTermBelongsToThePreviousSeptember() {
    assertThat(AcademicYear.of(LocalDate.of(2027, 1, 4))).isEqualTo("2026/27");
    assertThat(AcademicYear.of(LocalDate.of(2027, 7, 21))).isEqualTo("2026/27");
  }

  @Test
  @DisplayName("31 August is the last day of the outgoing year, 1 September the first of the next")
  void theBoundaryIsExact() {
    assertThat(AcademicYear.of(LocalDate.of(2026, 8, 31))).isEqualTo("2025/26");
    assertThat(AcademicYear.of(LocalDate.of(2026, 9, 1))).isEqualTo("2026/27");
  }

  @Test
  @DisplayName("a decade rollover still formats as two digits")
  void decadeRolloverFormatsAsTwoDigits() {
    // 2029/30 is fine, but 2009/10 must not render as 2009/1 and 2099/00 must not overflow.
    assertThat(AcademicYear.format(2009)).isEqualTo("2009/10");
    assertThat(AcademicYear.format(2099)).isEqualTo("2099/00");
  }
}
