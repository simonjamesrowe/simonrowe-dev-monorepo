package com.simonrowe.aggregation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The self-hosted page reader: a headless browser in the {@code page-reader} container, reached
 * through software-factory's {@code POST /api/page-reads}.
 *
 * @param enabled whether to ask at all. Off, a blocked page goes straight to the reader fallback.
 * @param timeout how long one read may take. Longer than the factory's own wait
 *     ({@code factory.pageread.wait-for}, 100s), so a slow read comes back as {@code RUNNING}
 *     rather than as a timeout here.
 */
@ConfigurationProperties("aggregation.page-reader")
public record PageReaderProperties(boolean enabled, Duration timeout) {

  /** Applies defaults for anything unset. */
  public PageReaderProperties {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      timeout = Duration.ofSeconds(110);
    }
  }
}
