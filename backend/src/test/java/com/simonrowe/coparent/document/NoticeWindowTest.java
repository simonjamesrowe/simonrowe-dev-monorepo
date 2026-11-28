package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** One email for a sitting: fifteen quiet minutes, never more than two hours after the first. */
class NoticeWindowTest {

  private static final Instant START = Instant.parse("2026-11-28T10:00:00Z");

  @ParameterizedTest(name = "change {0} min in sends at {1} min")
  @CsvSource({
      // The first change: fifteen minutes on.
      "0, 15",
      // Each later change pushes it back.
      "10, 25",
      "100, 115",
      // Until the two-hour cap holds it.
      "105, 120",
      "110, 120",
      "119, 120"
  })
  void debouncesUpToTheCap(final long changeMinute, final long sendMinute) {
    final Instant now = START.plusSeconds(changeMinute * 60);

    assertThat(NoticeWindow.sendAfter(START, now)).isEqualTo(START.plusSeconds(sendMinute * 60));
  }

  @ParameterizedTest
  @CsvSource({"0", "45"})
  void changeWithNoBatchStartsOne(final long minute) {
    final Instant now = START.plusSeconds(minute * 60);

    assertThat(NoticeWindow.sendAfter(null, now)).isEqualTo(now.plus(NoticeWindow.QUIET));
  }
}
