package com.simonrowe.factory.pageread.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for page reads.
 *
 * @param enabled accepts reads at {@code POST /api/page-reads}; true on software-factory only.
 *     Off, the endpoint answers 503 and the backend falls back to its other readers.
 * @param waitFor how long one request waits for its read before answering {@code RUNNING}. The
 *     backend's own timeout must be longer.
 */
@ConfigurationProperties("factory.pageread")
public record PageReadProperties(boolean enabled, Duration waitFor) {

  public PageReadProperties {
    waitFor = waitFor == null || waitFor.isNegative() || waitFor.isZero()
        ? Duration.ofSeconds(100)
        : waitFor;
  }
}
