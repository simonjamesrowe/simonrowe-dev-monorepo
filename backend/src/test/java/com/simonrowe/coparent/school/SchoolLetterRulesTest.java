package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.simonrowe.coparent.model.Child;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The fixed rules for personal letters and report prompts. Every name is invented. */
class SchoolLetterRulesTest {

  private static final LocalDate ON = LocalDate.of(2026, 11, 26);
  private static final ObjectId FAMILY = new ObjectId();
  /** Year 4 on {@link #ON}. */
  private static final Child MIA = child("Mia Taylor", LocalDate.of(2018, 3, 1));
  /** Year 1 on {@link #ON}. */
  private static final Child LEO = child("Leo Taylor", LocalDate.of(2021, 1, 10));
  private static final List<Child> CHILDREN = List.of(MIA, LEO);

  @Test
  void bothChildrenNamedWithTheFamilySurnameInTheFileName() {
    assertThat(SchoolLetterRules.childrenNamed("Letter from the office", "Mia and Leo Taylor.pdf",
        CHILDREN, ON)).containsExactly(MIA, LEO);
  }

  @Test
  void firstNameWithTheChildsOwnYearInTheFileName() {
    assertThat(SchoolLetterRules.childrenNamed("Instrumental lesson reports",
        "Instrumental lessons reports - Y4 Mia.pdf", CHILDREN, ON)).containsExactly(MIA);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Mia Taylor's attendance", "Re: MIA TAYLOR", "Report: Taylor, Mia"})
  void fullNameOrFirstNameAndSurnameInTheSubject(final String subject) {
    assertThat(SchoolLetterRules.childrenNamed(subject, "letter.pdf", CHILDREN, ON))
        .containsExactly(MIA);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Stars of the week: Mia W | stars.pdf",
      "Y1 Mia | trip.pdf",
      "Year 6 residential | Kingswood consent.pdf",
      "Taylor Shaw catering menu | menu.pdf",
      "Miami trip | Taylor.pdf",
      "Amelia's certificate | Amelia Taylor.pdf"})
  void anotherChildOrWordThatOnlyLooksLikeNameIsNotMatched(final String subject,
      final String file) {
    // "Taylor.pdf" with "Miami" names nobody: names are whole words, never substrings.
    assertThat(SchoolLetterRules.childrenNamed(subject, file, List.of(MIA), ON)).isEmpty();
  }

  @Test
  void singleNameChildNeedsTheSurnameOfSibling() {
    final Child robin = child("Robin", LocalDate.of(2018, 3, 1));
    assertThat(SchoolLetterRules.childrenNamed("Robin", "note.pdf", List.of(robin), ON))
        .as("a first name alone is not enough").isEmpty();
    assertThat(SchoolLetterRules.childrenNamed("Robin and Leo Taylor", "note.pdf",
        List.of(robin, LEO), ON)).containsExactly(robin, LEO);
  }

  @Test
  void rulesStayLinearOnHundredThousandCharacters() {
    final String names = "Mia Taylor " + "Mia ".repeat(12_500);
    final String hostile = names + "'".repeat(25_000) + "-a".repeat(12_500);
    assertThat(hostile.length()).isGreaterThanOrEqualTo(100_000);
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      assertThat(SchoolLetterRules.childrenNamed(hostile, hostile, CHILDREN, ON))
          .containsExactly(MIA);
      assertThat(SchoolLetterRules.reportFirstName("A report for " + "Mia ".repeat(25_000)))
          .isEmpty();
      assertThat(SchoolLetterRules.reportLink("https://" + "a".repeat(100_000), List.of()))
          .isEmpty();
      assertThat(SchoolLetterRules.letterTitle("Re: ".repeat(25_000) + "Trip", null))
          .isEqualTo("Trip");
    });
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "A report for Mia is ready | Mia",
      "a report for mia is ready! | mia",
      "  A report for Mia Taylor is ready | Mia"})
  void readsTheFirstNameFromTheReportSubject(final String subject, final String name) {
    assertThat(SchoolLetterRules.reportFirstName(subject)).contains(name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Re: A report for Mia is ready", "A report for Mia", "Reports ready",
      "Your report for Mia is ready"})
  void otherSubjectsAreNotReportPrompts(final String subject) {
    assertThat(SchoolLetterRules.reportFirstName(subject)).isEmpty();
  }

  @Test
  void reportChildIsTheOneChildWithThatFirstName() {
    assertThat(SchoolLetterRules.reportChild("MIA", CHILDREN)).contains(MIA);
    assertThat(SchoolLetterRules.reportChild("Zoe", CHILDREN)).isEmpty();
    assertThat(SchoolLetterRules.reportChild("Mia",
        List.of(MIA, child("Mia Jones", LocalDate.of(2019, 1, 1))))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://app.insighttracking.com.evil.example/report/1",
      "https://evil.example/app.insighttracking.com/report",
      "http://app.insighttracking.com/report/1",
      "https://user@app.insighttracking.com/report/1",
      "https://app.insighttracking.com:8443/report/1",
      "https://insighttracking.com/report/1",
      "javascript:alert(1)",
      "not a url at all"})
  void refusesAnyLinkNotOnTheReportingSystemsOrigin(final String link) {
    assertThat(SchoolLetterRules.trustedReportLink(link)).isEmpty();
    assertThat(SchoolLetterRules.reportLink("Read it: " + link, List.of(link))).isEmpty();
  }

  @Test
  void acceptsTheReportingSystemsOwnLinkFromTheLinksOrTheText() {
    assertThat(SchoolLetterRules.reportLink(null,
        List.of("https://app.insighttracking.com.evil.example/x",
            "https://APP.insighttracking.com/parent/report/42")))
        .contains("https://APP.insighttracking.com/parent/report/42");
    assertThat(SchoolLetterRules.reportLink(
        "Log in at https://app.insighttracking.com/parent/report/42. Thanks", null))
        .contains("https://app.insighttracking.com/parent/report/42");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "2026-07-14 | End-of-year report 2025/26",
      "2026-09-03 | Autumn report 2026/27",
      "2026-11-24 | Autumn report 2026/27",
      "2027-01-20 | Spring report 2026/27",
      "2027-04-30 | Spring report 2026/27",
      "2027-05-12 | School report 2026/27",
      "2027-06-01 | School report 2026/27",
      "2027-08-20 | School report 2026/27"})
  void titlesReportFromTheMonthAndAcademicYear(final LocalDate sentOn, final String title) {
    assertThat(SchoolLetterRules.reportTitle(sentOn)).isEqualTo(title);
  }

  @Test
  void titlesLetterFromTheSubjectThenTheFileName() {
    assertThat(SchoolLetterRules.letterTitle("Fwd: RE: Year 6 residential: consent form",
        "x.pdf")).isEqualTo("Year 6 residential: consent form");
    assertThat(SchoolLetterRules.letterTitle("  ", "Kingswood_consent.pdf"))
        .isEqualTo("Kingswood consent");
    assertThat(SchoolLetterRules.letterTitle(null, null)).isEqualTo("School letter");
    assertThat(SchoolLetterRules.letterTitle("x".repeat(300), null)).hasSize(120);
  }

  @Test
  void namesYearsAsTheSchoolWritesThem() {
    assertThat(SchoolLetterRules.namesYear(SchoolLetterRules.words("Year 4 trip"), "Year 4"))
        .isTrue();
    assertThat(SchoolLetterRules.namesYear(SchoolLetterRules.words("Yr4"), "Year 4")).isTrue();
    assertThat(SchoolLetterRules.namesYear(SchoolLetterRules.words("Y 4"), "Year 4")).isTrue();
    assertThat(SchoolLetterRules.namesYear(SchoolLetterRules.words("Y44"), "Year 4")).isFalse();
    assertThat(SchoolLetterRules.namesYear(SchoolLetterRules.words("Reception"), "Reception"))
        .isTrue();
  }

  private static Child child(final String name, final LocalDate dateOfBirth) {
    final Instant now = Instant.parse("2026-09-01T00:00:00Z");
    return new Child(new ObjectId(), FAMILY, name, dateOfBirth, null, null, null, null, now, now,
        null);
  }
}
