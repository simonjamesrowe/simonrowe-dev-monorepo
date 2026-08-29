package com.simonrowe.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShortLinkSluggerTest {

  /**
   * Every slug this class can emit must match: 1–20 characters of {@code [a-z0-9-]},
   * with no leading or trailing hyphen. Asserted on the output of each case rather than
   * only checking a specific value, because the ceiling and the shape are the properties
   * that keep addresses speakable — the exact value is only an example of them.
   */
  private static final Pattern VALID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?$");

  @Nested
  @DisplayName("slugify")
  class Slugify {

    @Test
    void takesWholeWordsUpToTheCeilingRatherThanChoppingMidWord() {
      assertThat(ShortLinkSlugger.slugify("Exactly-once semantics in Kafka"))
          .isEqualTo("exactly-once");
    }

    @ParameterizedTest
    @CsvSource({
        "'Hello World', hello-world",
        "'  Leading and trailing  ', leading-and-trailing",
        "'Multiple   spaces', multiple-spaces",
        "'Punctuation! Everywhere?', punctuation",
        "'UPPER CASE TITLE', upper-case-title",
        "'C# and .NET', c-and-net",
        "'2026 in review', 2026-in-review",
    })
    void normalisesToLowercaseHyphenatedWords(final String title, final String expected) {
      assertThat(ShortLinkSlugger.slugify(title)).isEqualTo(expected);
    }

    @Test
    void stripsAccentsToTheirPlainLetters() {
      assertThat(ShortLinkSlugger.slugify("Café déjà vu")).isEqualTo("cafe-deja-vu");
    }

    @Test
    void stripsAccentsFromPrecomposedAndCombiningFormsAlike() {
      // U+00E9 versus "e" + U+0301 (combining acute) render identically. Both must
      // normalise to the same slug, or the same visible title pasted from two sources
      // would mint two different links.
      String precomposed = "résumé";
      String decomposed = Normalizer.normalize(precomposed, Normalizer.Form.NFD);
      assertThat(decomposed).isNotEqualTo(precomposed);
      assertThat(ShortLinkSlugger.slugify(precomposed)).isEqualTo("resume");
      assertThat(ShortLinkSlugger.slugify(decomposed)).isEqualTo("resume");
    }

    @Test
    void neverExceedsTheCeiling() {
      String slug = ShortLinkSlugger.slugify(
          "An extraordinarily long headline about distributed systems");
      assertThat(slug).hasSizeLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH);
      assertThat(slug).matches(VALID);
      // Whole words only, so it must not end mid-word.
      assertThat(slug).isEqualTo("an-extraordinarily");
    }

    @Test
    void hardCutsSingleFirstWordsThatAlreadyExceedTheCeiling() {
      // Falling through to a random code here would be worse than a truncation: the
      // reader loses every clue about what the link points at.
      String slug = ShortLinkSlugger.slugify("Internationalisationalisation matters");
      assertThat(slug).hasSize(ShortLinkSlugger.MAX_LENGTH);
      assertThat(slug).isEqualTo("internationalisation");
      assertThat(slug).matches(VALID);
    }

    @Test
    void collapsesRunsOfSeparatorsRatherThanEmittingDoubleHyphens() {
      assertThat(ShortLinkSlugger.slugify("Kafka -- the good parts"))
          .isEqualTo("kafka-the-good-parts")
          .matches(VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "---", "🎉🚀", "日本語のタイトル"})
    void fallsBackToRandomCodeWhenNothingUsableSurvives(final String title) {
      String slug = ShortLinkSlugger.slugify(title);
      assertThat(slug).hasSize(ShortLinkSlugger.RANDOM_CODE_LENGTH);
      assertThat(slug).matches("^[a-z0-9]{6}$");
    }

    @Test
    void fallsBackForNullTitles() {
      assertThat(ShortLinkSlugger.slugify(null)).matches("^[a-z0-9]{6}$");
    }
  }

  @Nested
  @DisplayName("withSuffix")
  class WithSuffix {

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 9, 10, 42, 99})
    void keepsTheCeilingAtEveryAttemptNumber(final int attempt) {
      // The design's literal "cut to 17 characters plus -2, -3" holds only while the
      // suffix is two characters. Reserving ("-" + n).length() is what makes -10 and -99
      // safe too.
      String base = "an-extraordinarily";
      String slug = ShortLinkSlugger.withSuffix(base, attempt);
      assertThat(slug)
          .hasSizeLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH)
          .endsWith("-" + attempt)
          .matches(VALID);
    }

    @Test
    void leavesShortBasesIntact() {
      assertThat(ShortLinkSlugger.withSuffix("kafka", 2)).isEqualTo("kafka-2");
    }

    @Test
    void prefersWordBoundariesWhenItHasToCut() {
      // "exactly-once-semantics" is 22; with "-2" reserved the base may be 18.
      // Cutting to 18 lands mid-word ("exactly-once-seman"), so it backs off to the
      // previous boundary.
      assertThat(ShortLinkSlugger.withSuffix("exactly-once-semantics", 2))
          .isEqualTo("exactly-once-2");
    }

    @Test
    void hardCutsWhenNoWordBoundaryFits() {
      String slug = ShortLinkSlugger.withSuffix("internationalisatio", 7);
      assertThat(slug).hasSizeLessThanOrEqualTo(ShortLinkSlugger.MAX_LENGTH);
      assertThat(slug).endsWith("-7").matches(VALID);
    }

    @Test
    void neverEmitsDoubleHyphensWhenTheCutLandsOnSeparators() {
      // Cutting "abcdefgh-ijklmnopqrs" to 18 gives "abcdefgh-ijklmnopq"; a base that ends
      // on the separator itself must not produce "abcdefgh--2".
      assertThat(ShortLinkSlugger.withSuffix("abcdefgh-", 2)).doesNotContain("--");
    }
  }

  @Nested
  @DisplayName("randomCode")
  class RandomCode {

    @Test
    void isSixLowercaseAlphanumerics() {
      assertThat(ShortLinkSlugger.randomCode()).matches("^[a-z0-9]{6}$");
    }

    @Test
    void doesNotRepeatItselfOverThousandDraws() {
      // Not a distribution test — just enough to catch a constant or a seeded Random.
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 1000; i++) {
        seen.add(ShortLinkSlugger.randomCode());
      }
      assertThat(seen).hasSizeGreaterThan(990);
    }
  }
}
