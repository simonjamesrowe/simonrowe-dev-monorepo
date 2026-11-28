package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.DueKind;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which due-date reminder, if any, a document needs today. Pure: today is passed in, so the
 * daily job's clock and time zone stay outside and every threshold can be tested by date.
 *
 * <p>An expiry warns at six and three months; a review at one month and one week. A threshold
 * is open from the day it is reached until the due date. When several are open at once (the
 * document was added close to its date, or the job was down for a while), one email goes, for
 * the nearest threshold, and every open threshold is marked sent so the later days do not send
 * the farther ones again. Keys are {@code <dueOn>:<threshold>}, so a renewal with a new date
 * starts afresh and an old key never blocks it.
 */
public final class ReminderWindows {

  /** One threshold before a due date. */
  public record Threshold(String label, Period before) {
  }

  /**
   * A reminder to send.
   *
   * @param threshold the nearest open threshold, which the email is worded for
   * @param keys every open threshold's key, all to be marked sent with this one email
   */
  public record Due(Threshold threshold, List<String> keys) {
    public Due {
      keys = List.copyOf(keys);
    }

    /** The key the conditional update checks: this reminder has not been sent yet. */
    public String key() {
      return keys.getLast();
    }
  }

  static final List<Threshold> EXPIRES = List.of(
      new Threshold("6m", Period.ofMonths(6)), new Threshold("3m", Period.ofMonths(3)));
  static final List<Threshold> REVIEW = List.of(
      new Threshold("1m", Period.ofMonths(1)), new Threshold("1w", Period.ofWeeks(1)));

  /** The farthest a reminder ever looks ahead, which bounds the daily job's query. */
  public static final Period LOOKAHEAD = Period.ofMonths(6);

  private ReminderWindows() {
  }

  /** The reminder a document due on {@code dueOn} needs on {@code today}, if any. */
  public static Optional<Due> due(final LocalDate today, final LocalDate dueOn,
      final DueKind kind, final Set<String> sent) {
    if (today == null || dueOn == null || kind == null || dueOn.isBefore(today)) {
      return Optional.empty();
    }
    final List<Threshold> thresholds = kind == DueKind.EXPIRES ? EXPIRES : REVIEW;
    final List<String> open = new ArrayList<>();
    Threshold nearest = null;
    for (final Threshold threshold : thresholds) {
      if (!today.isBefore(dueOn.minus(threshold.before()))) {
        open.add(key(dueOn, threshold));
        nearest = threshold;
      }
    }
    if (nearest == null || (sent != null && sent.contains(key(dueOn, nearest)))) {
      return Optional.empty();
    }
    return Optional.of(new Due(nearest, open));
  }

  static String key(final LocalDate dueOn, final Threshold threshold) {
    return dueOn + ":" + threshold.label();
  }
}
