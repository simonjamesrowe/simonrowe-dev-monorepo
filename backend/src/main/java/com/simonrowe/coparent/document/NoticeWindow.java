package com.simonrowe.coparent.document;

import java.time.Duration;
import java.time.Instant;

/**
 * When a batched document email may go: fifteen minutes after the latest change, so a parent
 * adding six documents in a sitting sends one email, but never later than two hours after the
 * first change, so a long sitting still sends.
 */
public final class NoticeWindow {

  public static final Duration QUIET = Duration.ofMinutes(15);
  public static final Duration CAP = Duration.ofHours(2);

  private NoticeWindow() {
  }

  /**
   * The send time for a batch that started at {@code firstAt} and last changed at {@code now}.
   * A null {@code firstAt} means this change starts the batch.
   */
  public static Instant sendAfter(final Instant firstAt, final Instant now) {
    final Instant start = firstAt == null ? now : firstAt;
    final Instant debounced = now.plus(QUIET);
    final Instant capped = start.plus(CAP);
    return debounced.isBefore(capped) ? debounced : capped;
  }
}
