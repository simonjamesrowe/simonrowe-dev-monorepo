package com.simonrowe.coparent.expense;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Formats pence as pounds sterling for anything a parent reads outside the app, such as email.
 * Pinned to the UK locale and GBP rather than the JVM default, so a host with a US default locale
 * still writes "£45.00" and never "$45.00".
 */
public final class ExpenseMoney {

  private ExpenseMoney() {
  }

  /** Formats pence as, for example, {@code £1,234.50}. */
  public static String format(final long pence) {
    // NumberFormat is not thread-safe, so one is created per call.
    final NumberFormat format = NumberFormat.getCurrencyInstance(Locale.UK);
    format.setCurrency(Currency.getInstance("GBP"));
    return format.format(BigDecimal.valueOf(pence, 2));
  }
}
