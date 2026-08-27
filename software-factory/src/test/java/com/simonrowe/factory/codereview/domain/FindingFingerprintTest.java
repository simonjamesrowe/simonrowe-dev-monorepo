package com.simonrowe.factory.codereview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The fingerprint is what lets a re-review recognise a finding it already reported, so what it
 * ignores matters more than what it covers.
 */
class FindingFingerprintTest {

  private static ReviewFinding finding(
      final Severity severity, final String file, final int line, final String title) {
    return new ReviewFinding(severity, file, line, title, "Because.", "Fix it.");
  }

  @Test
  void sameFileAndTitleFingerprintTheSame() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing null check"))
        .isEqualTo(FindingFingerprint.of("src/App.java", "Missing null check"));
  }

  @Test
  void caseDoesNotChangeIdentity() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing Null Check"))
        .isEqualTo(FindingFingerprint.of("src/App.java", "missing null check"));
  }

  @Test
  void punctuationDoesNotChangeIdentity() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing null check!"))
        .isEqualTo(FindingFingerprint.of("src/App.java", "Missing null-check"));
  }

  @Test
  void whitespaceRunsAndSurroundingSpaceDoNotChangeIdentity() {
    assertThat(FindingFingerprint.of("src/App.java", "  Missing   null\tcheck  "))
        .isEqualTo(FindingFingerprint.of("src/App.java", "Missing null check"));
  }

  /** Lines move on every rebase; a fingerprint that moved with them would orphan every finding. */
  @Test
  void lineIsNotPartOfIdentity() {
    assertThat(FindingFingerprint.of(finding(Severity.WARNING, "src/App.java", 12, "Bad")))
        .isEqualTo(FindingFingerprint.of(finding(Severity.WARNING, "src/App.java", 900, "Bad")));
  }

  /** The model re-grades between runs, so severity must not be able to change identity. */
  @Test
  void severityIsNotPartOfIdentity() {
    assertThat(FindingFingerprint.of(finding(Severity.SUGGESTION, "src/App.java", 12, "Bad")))
        .isEqualTo(FindingFingerprint.of(finding(Severity.CRITICAL, "src/App.java", 12, "Bad")));
  }

  @Test
  void explanationAndRecommendationAreNotPartOfIdentity() {
    ReviewFinding one =
        new ReviewFinding(Severity.WARNING, "src/App.java", 12, "Bad", "One reason.", "One fix.");
    ReviewFinding other =
        new ReviewFinding(
            Severity.WARNING, "src/App.java", 12, "Bad", "A quite different reason.", "Another.");

    assertThat(FindingFingerprint.of(one)).isEqualTo(FindingFingerprint.of(other));
  }

  @Test
  void sameTitleInDifferentFileIsDifferentFinding() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing null check"))
        .isNotEqualTo(FindingFingerprint.of("src/Other.java", "Missing null check"));
  }

  @Test
  void differentTitleInTheSameFileIsDifferentFinding() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing null check"))
        .isNotEqualTo(FindingFingerprint.of("src/App.java", "Unbounded loop"));
  }

  /**
   * The separator earns its place here.
   *
   * <p>Concatenating the two fields without one would make {@code ("ab", "c")} and {@code ("a",
   * "bc")} the same finding. A space would not do either — both fields legitimately contain
   * spaces, which is exactly what this pair exercises.
   */
  @Test
  void fileAndTitleCannotBleedIntoEachOther() {
    assertThat(FindingFingerprint.of("src/a b", "c"))
        .isNotEqualTo(FindingFingerprint.of("src/a", "b c"));
  }

  /** A punctuation-only title still needs an identity of its own rather than a shared empty one. */
  @Test
  void titleThatNormalisesToNothingFallsBackToTheRawTitle() {
    String first = FindingFingerprint.of("src/App.java", "???");
    String second = FindingFingerprint.of("src/App.java", "!!!");

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isNotBlank();
  }

  @Test
  void renderedAsLowercaseHexSha256() {
    assertThat(FindingFingerprint.of("src/App.java", "Missing null check"))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void nullFileOrTitleDoesNotThrow() {
    assertThat(FindingFingerprint.of(null, null)).matches("[0-9a-f]{64}");
    assertThat(FindingFingerprint.of("src/App.java", null)).matches("[0-9a-f]{64}");
    assertThat(FindingFingerprint.of(null, "Bad")).matches("[0-9a-f]{64}");
  }

  @Test
  void normalisationIsIndependentOfTheDefaultLocale() {
    // A Turkish default locale uppercases "i" to a dotted capital, which is the classic way a
    // locale-sensitive toLowerCase silently changes an identifier. Locale.ROOT is what stops it.
    assertThat(FindingFingerprint.normalise("INDEX")).isEqualTo("index");
  }
}
