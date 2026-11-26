package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchoolEventTextTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Harriet's class trip|harriet",
      "Year 3 Local Area Walk – Harriet's class|harriet",
      "Omar' Class Assembly|omar",
      "Omars' Class Trip|omar",
      "Omar’s class visit to the museum|omar",
      "Elis's class trip|eli",
  })
  void readsTheClassNamedAsPossessive(final String title, final String expected) {
    assertThat(SchoolEventText.classesNamed(title)).containsExactly(expected);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Dance class",
      "Art class for Year 4",
      "Children's class assembly",
      "Parents' class visit",
      "Winter Fair",
  })
  void doesNotTakeAnOrdinaryWordForClass(final String title) {
    assertThat(SchoolEventText.classesNamed(title)).isEmpty();
  }

  @Test
  void twoSpellingsOfOneClassCompareEqual() {
    assertThat(SchoolEventText.namesClass("Wills' Class Trip", "Will")).isTrue();
    assertThat(SchoolEventText.namesClass("Will's class visit", "Wills")).isTrue();
    assertThat(SchoolEventText.namesClass("Bears Class Trip", "Bears")).isTrue();
    assertThat(SchoolEventText.namesClass("Bears Class Trip", "Lions")).isFalse();
    assertThat(SchoolEventText.namesClass("Harriet's class trip", "Omar")).isFalse();
  }

  @Test
  void classNameIsMatchedLiterallyNotAsPattern() {
    assertThat(SchoolEventText.namesClass("Anything class", ".*")).isFalse();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Year 3 Local Area Walk|Year 3",
      "Year 3 and Year 4 Winter Sing Along|Year 3,Year 4",
      "Years 5 and 6 performance|Year 5,Year 6",
      "Years 3-6 assembly|Year 3,Year 4,Year 5,Year 6",
      "New parent tour (Reception)|Reception",
  })
  void readsTheYearGroupsNamedInTitle(final String title, final String expected) {
    assertThat(SchoolEventText.yearGroupsNamed(title))
        .containsExactly(expected.split(","));
  }

  @Test
  void bareNumberOrLongerNumberIsNotYear() {
    assertThat(SchoolEventText.yearGroupsNamed("Year 10 open evening")).isEmpty();
    assertThat(SchoolEventText.yearGroupsNamed("Christmas Fair 2026")).isEmpty();
  }

  @Test
  void hugeTitleShapedToBacktrackIsReadQuickly() {
    final String hostile = "a'".repeat(25_000) + "Year 3, ".repeat(10_000) + "x".repeat(30_000);
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      SchoolEventText.classesNamed(hostile);
      SchoolEventText.yearGroupsNamed(hostile);
      SchoolEventText.namesClass(hostile, "Harriet");
    });
  }
}
