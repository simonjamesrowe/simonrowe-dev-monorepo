package com.simonrowe.narration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for sentence-by-sentence synthesis and the read-along timings it produces.
 *
 * <p>Separate from {@link NarrationProperties} so adding these did not reshape a record that a
 * handful of tests build positionally.
 *
 * @param concurrency how many sentences of one narration are synthesised at once. A long blog
 *     is a few hundred sentences, so one at a time would take minutes
 * @param segmentAttempts tries per sentence before the narration fails. A few hundred requests
 *     make a transient error likely somewhere, and failing the whole narration on one would
 *     pay for every other sentence again on the retry
 * @param upgradeEnabled whether {@link NarrationTimingsUpgradeSweep} re-makes audio that
 *     predates timings
 * @param upgradeBudgetShare the share of the monthly character budget the upgrade may use. It
 *     only ever spends below this line, so what a reader asks for always has room
 * @param upgradeInterval how long the upgrade waits between narrations
 * @param upgradeMaxAttempts tries per narration before the upgrade gives up on it
 */
@ConfigurationProperties(prefix = "narration.timings")
public record NarrationTimingsProperties(
    int concurrency,
    int segmentAttempts,
    boolean upgradeEnabled,
    double upgradeBudgetShare,
    Duration upgradeInterval,
    int upgradeMaxAttempts
) {

  public NarrationTimingsProperties {
    concurrency = Math.max(1, concurrency);
    segmentAttempts = Math.max(1, segmentAttempts);
    upgradeBudgetShare = Math.clamp(upgradeBudgetShare, 0.0, 1.0);
    upgradeInterval = upgradeInterval == null ? Duration.ofMinutes(5) : upgradeInterval;
    upgradeMaxAttempts = Math.max(1, upgradeMaxAttempts);
  }
}
