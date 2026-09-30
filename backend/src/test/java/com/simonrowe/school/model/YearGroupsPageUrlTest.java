package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reading a year group out of a school page's address.
 *
 * <p>The addresses are the school's real ones, taken from its site on 2026-09-30. Every year's
 * home-learning PDF used to be stored with no year group and the same title, so the assistant
 * called the Year 6 spelling sheet "Year 3" because that was what the parent had asked about.
 */
class YearGroupsPageUrlTest {

  private static final String BASE = "https://www.kilmorieschool.co.uk";

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "/year-one, Year 1",
      "/year-two, Year 2",
      "/year-three, Year 3",
      "/year-three-home-learning, Year 3",
      "/year-4, Year 4",
      "/year-four-home-learning, Year 4",
      "/year-five-home-learning, Year 5",
      "/year-six, Year 6",
      "/year-6-spine-poetry-festival-2023, Year 6",
      "/year3-stonehenge, Year 3",
      "/year4-sustainability-and-plastic-pollution, Year 4",
      "/year5deptfordcreeksidediscoverycentre2023, Year 5",
      "/Year-Three, Year 3",
      "/reception, Reception",
  })
  @DisplayName("a year page and its children name their year")
  void yearPagesNameTheirYear(final String path, final String year) {
    assertThat(YearGroups.fromPageUrl(BASE + path)).containsExactly(year);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/year-group-pages",
      "/school-news/year-5-football",
      "/photo-gallery/year-3-stone-age-assembly",
      "/years-3-and-4-football-festival",
      "/year-sixteen",
      "/year-7",
      "/year-60",
      "/curriculum",
      "/page/?title=Home+Learning&pid=158",
      "/",
      "",
  })
  @DisplayName("anything else belongs to no year in particular")
  void otherPagesNameNoYear(final String path) {
    assertThat(YearGroups.fromPageUrl(BASE + path)).isEmpty();
  }

  @Test
  @DisplayName("no address, or one that cannot be parsed, names no year")
  void unusableAddressesNameNoYear() {
    assertThat(YearGroups.fromPageUrl(null)).isEmpty();
    assertThat(YearGroups.fromPageUrl("  ")).isEmpty();
    assertThat(YearGroups.fromPageUrl("https://exa mple.test/year-three")).isEmpty();
  }

  @Test
  @DisplayName("an enormous path segment is answered at once")
  void enormousSegmentIsLinear() {
    // The pattern is anchored with no nested quantifiers, so there is nothing to backtrack over,
    // but this runs on addresses scraped from someone else's HTML, so check it anyway.
    final String path = "/year-" + "three-".repeat(20_000) + "x".repeat(100_000);

    final List<String> years = assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> YearGroups.fromPageUrl(BASE + path));

    assertThat(years).containsExactly("Year 3");
  }
}
