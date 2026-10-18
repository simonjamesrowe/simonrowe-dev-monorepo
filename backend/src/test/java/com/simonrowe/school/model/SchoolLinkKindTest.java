package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link SchoolLink#likelyKind()}'s image test: the old full-match regex's answers, linearly. */
class SchoolLinkKindTest {

  private static final String OLD_REGEX = ".*\\.(jpg|jpeg|png|gif|webp)(\\?.*)?$";

  @ParameterizedTest
  @ValueSource(strings = {
      "https://e.com/a.jpg", "https://e.com/a.JPEG", "https://e.com/a.png?w=200",
      "https://e.com/a.gif?", "https://e.com/a.webp?x=1.txt", "https://e.com/a.png.html",
      "https://e.com/a.pngx", "https://e.com/a.png#frag", "https://e.com/a.jpg?q=1?r=2",
      "https://e.com/png", "https://e.com/a.svg", ".jpg", "https://e.com/a.png?x.jpg"})
  void imageAnswerAgreesWithTheOldRegex(final String url) {
    final boolean old = url.toLowerCase().matches(OLD_REGEX);

    assertThat("Image".equals(link(url).likelyKind())).isEqualTo(old);
  }

  @Test
  void isLinearOnHostileInput() {
    final SchoolLink hostile = link("https://e.com/" + ".jpg".repeat(25_000) + "x");

    assertTimeoutPreemptively(Duration.ofSeconds(1), hostile::likelyKind);
  }

  private static SchoolLink link(final String url) {
    return new SchoolLink("id", "doc", url, null, null, null, null, null);
  }
}
