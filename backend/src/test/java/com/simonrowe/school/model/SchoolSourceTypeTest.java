package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the source precedence, which is declaration order and is silent when it is wrong.
 *
 * <p>{@link SchoolSourceType#precedence()} derives from the ordinal, so reordering the enum
 * changes which source wins when two describe the same event — with no compile error, no test
 * failure anywhere else, and no log line. The failure mode is an answer that is confidently
 * wrong about a date, which is the single worst thing this system produces.
 *
 * <p>The pasted-note ordering is the one that earns its place here. A note and the school's own
 * page routinely describe the same open evening — the note names it, the page behind the link in
 * that note gives the time and the booking address — and because
 * {@link com.simonrowe.school.ingest.SchoolIds#eventId} keys on date and title rather than on
 * source, they land on the same row. This ordering is the whole of what decides that the page
 * wins.
 */
class SchoolSourceTypeTest {

  @Test
  @DisplayName("the calendar feed beats every other source")
  void calendarWins() {
    for (SchoolSourceType other : SchoolSourceType.values()) {
      assertThat(SchoolSourceType.moreAuthoritative(SchoolSourceType.CALENDAR_FEED, other))
          .isEqualTo(SchoolSourceType.CALENDAR_FEED);
    }
  }

  @Test
  @DisplayName("a school's own page beats a note somebody typed from a group chat")
  void publishedPageBeatsPastedNote() {
    assertThat(SchoolSourceType.moreAuthoritative(
        SchoolSourceType.PASTED_NOTE, SchoolSourceType.EXTERNAL_PAGE))
        .isEqualTo(SchoolSourceType.EXTERNAL_PAGE);
  }

  @Test
  @DisplayName("a pasted note is the least authoritative source there is")
  void pastedNoteIsLast() {
    for (SchoolSourceType other : SchoolSourceType.values()) {
      if (other != SchoolSourceType.PASTED_NOTE) {
        assertThat(SchoolSourceType.moreAuthoritative(SchoolSourceType.PASTED_NOTE, other))
            .as("%s should beat a transcription of somebody else's message", other)
            .isEqualTo(other);
      }
    }
  }

  @Test
  @DisplayName("an external page ranks with the school's own website, not above it")
  void externalPageRanksBelowTheSchoolsOwnSite() {
    assertThat(SchoolSourceType.moreAuthoritative(
        SchoolSourceType.EXTERNAL_PAGE, SchoolSourceType.WEBSITE_PAGE))
        .isEqualTo(SchoolSourceType.WEBSITE_PAGE);
    assertThat(SchoolSourceType.moreAuthoritative(
        SchoolSourceType.EXTERNAL_PAGE, SchoolSourceType.EMAIL))
        .isEqualTo(SchoolSourceType.EMAIL);
  }
}
