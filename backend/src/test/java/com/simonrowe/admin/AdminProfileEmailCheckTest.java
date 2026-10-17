package com.simonrowe.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The profile email check: the old regex's answers, without its backtracking. */
class AdminProfileEmailCheckTest {

  private static final String OLD_REGEX = "^[^@]+@[^@]+\\.[^@]+$";

  static List<String> samples() {
    return List.of(
        "simon@example.com", "a@b.c", "a@b.c.d", "first.last@sub.example.co.uk",
        "@example.com", "simon@", "simon@example", "simon@.com", "simon@example.",
        "simon@@example.com", "si@mon@example.com", "simon@ex.ample@x.y", "a@.", "a@..",
        "a@.b.", "a@b..c", "a@...", ".@a.b", "a.b@c", "a b@c d.e", "a@b\n.c", "@", "", ".");
  }

  @ParameterizedTest
  @MethodSource("samples")
  void agreesWithTheOldRegex(final String value) {
    assertThat(AdminProfileController.looksLikeEmail(value)).isEqualTo(value.matches(OLD_REGEX));
  }

  @Test
  void isLinearOnHostileInput() {
    // The shape that made the regex quadratic: a long domain full of dots that never ends well.
    final String hostile = "a@" + "b.".repeat(60_000) + "@";

    assertTimeoutPreemptively(Duration.ofSeconds(1),
        () -> assertThat(AdminProfileController.looksLikeEmail(hostile)).isFalse());
  }
}
