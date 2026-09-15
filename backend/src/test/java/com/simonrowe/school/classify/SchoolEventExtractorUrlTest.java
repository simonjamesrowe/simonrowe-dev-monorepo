package com.simonrowe.school.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one defence against an invented booking link.
 *
 * <p>The extractor asks a model to copy an address out of the text it is reading, and the whole
 * value of that field is that a parent can click it and book a place. A plausible-looking
 * address that goes nowhere is worse than no address at all, because it is offered under the
 * same confident citation as the date — and a URL is exactly the shape of thing a model will
 * complete rather than copy: the right host with a guessed path.
 *
 * <p>Verification is a plain substring test rather than any kind of parse, because the question
 * is "did you copy this from the document" and not "is this well formed".
 */
class SchoolEventExtractorUrlTest {

  private static final String BODY = """
      Harris Boys - East Dulwich - 17 Sept - \
      https://www.harrisdulwichboys.org.uk/admissions/open-events.
      Kingsdale, book first: https://kingsdalefoundationschool.org.uk/open-days/
      """;

  @Test
  @DisplayName("an address copied from the text is kept")
  void copiedUrlSurvives() {
    assertThat(SchoolEventExtractor.verbatimUrl(
        "https://kingsdalefoundationschool.org.uk/open-days/", BODY))
        .isEqualTo("https://kingsdalefoundationschool.org.uk/open-days/");
  }

  @Test
  @DisplayName("a completed path on the right host is discarded")
  void inventedPathIsDropped() {
    // The dangerous case, and the reason this is not a host check: the host is real, the page
    // is not, and nothing downstream can tell.
    assertThat(SchoolEventExtractor.verbatimUrl(
        "https://kingsdalefoundationschool.org.uk/open-days/book-now", BODY)).isNull();
  }

  @Test
  @DisplayName("a trailing full stop swept up from the sentence is trimmed, not fatal")
  void trailingPunctuationTrimmed() {
    assertThat(SchoolEventExtractor.verbatimUrl(
        "https://www.harrisdulwichboys.org.uk/admissions/open-events.", BODY))
        .isEqualTo("https://www.harrisdulwichboys.org.uk/admissions/open-events");
  }

  @Test
  @DisplayName("anything that is not an http address is dropped")
  void nonHttpDropped() {
    assertThat(SchoolEventExtractor.verbatimUrl("see the school office", BODY)).isNull();
    assertThat(SchoolEventExtractor.verbatimUrl("", BODY)).isNull();
    assertThat(SchoolEventExtractor.verbatimUrl(null, BODY)).isNull();
  }
}
