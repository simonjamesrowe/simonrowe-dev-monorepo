package com.simonrowe.factory.logwatch.api;

import java.time.Instant;

/**
 * The manual-scan request body. Every field is optional.
 *
 * @param windowStart window start, or null for the configured default window
 * @param windowEnd window end, or null for now
 * @param dryRun when true, nothing is created or commented on in Linear
 */
public record LogWatchScanRequest(Instant windowStart, Instant windowEnd, Boolean dryRun) {

  /** Whether this request asked for a dry run, treating an absent flag as false. */
  public boolean isDryRun() {
    return Boolean.TRUE.equals(dryRun);
  }
}
