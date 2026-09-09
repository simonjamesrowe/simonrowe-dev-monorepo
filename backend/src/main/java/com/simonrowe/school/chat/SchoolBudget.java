package com.simonrowe.school.chat;

import com.simonrowe.school.SchoolProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A hard daily ceiling on anonymous answering.
 *
 * <p>Rate limiting alone bounds one visitor; it does not bound the bill. This bounds the bill.
 * When the ceiling is reached the assistant says it is resting rather than continuing to spend,
 * which is a far better failure than an invoice.
 *
 * <p>Counts turns rather than tokens, deliberately. Token accounting needs the response before it
 * can charge for it, so the ceiling would always be exceeded by the request that crosses it, and
 * the arithmetic would depend on usage metadata being populated — which varies by model and by
 * whether a cache hit occurred. Turns are knowable in advance and are what an abuser consumes.
 *
 * <p>In-memory, which is correct only while production runs a single backend instance. It does
 * today; the same caveat is already recorded for {@code ReleaseSummarySweep}. A second instance
 * would double the effective ceiling rather than break anything.
 */
@Component
public class SchoolBudget {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolBudget.class);

  private final SchoolProperties properties;
  private final Clock clock;
  private final AtomicReference<LocalDate> currentDay = new AtomicReference<>();
  private final AtomicLong used = new AtomicLong();

  public SchoolBudget(final SchoolProperties properties, final Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Consumes one unit of the daily allowance.
   *
   * @return true when the turn may proceed, false when today's allowance is spent
   */
  public synchronized boolean tryConsume() {
    final LocalDate today = LocalDate.now(clock);
    if (!today.equals(currentDay.get())) {
      currentDay.set(today);
      used.set(0);
    }
    final long limit = properties.dailyTokenBudget();
    if (limit <= 0) {
      // Zero means "no anonymous answering", not "unlimited". An unset budget must not default
      // to spending without limit on an endpoint that needs no credentials to reach.
      return false;
    }
    if (used.get() >= limit) {
      LOG.warn("Daily school chat allowance of {} reached", limit);
      return false;
    }
    used.incrementAndGet();
    return true;
  }

  /**
   * How much of today's allowance has been used.
   *
   * @return the count of anonymous turns served today
   */
  public long usedToday() {
    return LocalDate.now(clock).equals(currentDay.get()) ? used.get() : 0;
  }
}
