package com.simonrowe.common;

import java.util.regex.Pattern;

/**
 * Renders a caller-supplied value safe to write into a log line.
 *
 * <p>Anything that reaches a log statement from a path variable, a request body or a
 * Kafka message is attacker-controlled. A value containing CR or LF splits one log line
 * into two, so an attacker who can choose it can forge entries that read as though the
 * application wrote them — log forging, which SonarQube reports as
 * {@code javasecurity:S5145}. Prod ships these lines to Grafana Loki, where a forged
 * entry is indistinguishable from a real one after the fact.
 *
 * <p>Control characters are replaced rather than stripped, so a hostile value stays
 * visible in the log as an anomaly instead of quietly reading as ordinary text. The
 * result is also truncated: a log line is not the place to echo an unbounded input.
 *
 * <p>Wrap the untrusted argument only. A JWT subject is signed by Auth0 and cannot carry
 * an injected newline, so wrapping it adds noise and no safety.
 */
public final class LogSafe {

  /** Longer than any identifier this application legitimately logs. */
  private static final int MAX_LENGTH = 256;

  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

  private static final String TRUNCATION_MARKER = "...";

  private LogSafe() {
  }

  /**
   * Returns {@code value} with control characters replaced and length capped.
   *
   * @param value the untrusted value, may be {@code null}
   * @return a single-line, bounded rendering safe to pass to a logger
   */
  public static String value(final String value) {
    if (value == null) {
      return "null";
    }
    String sanitised = CONTROL_CHARACTERS.matcher(value).replaceAll("_");
    if (sanitised.length() <= MAX_LENGTH) {
      return sanitised;
    }
    return sanitised.substring(0, MAX_LENGTH) + TRUNCATION_MARKER;
  }
}
