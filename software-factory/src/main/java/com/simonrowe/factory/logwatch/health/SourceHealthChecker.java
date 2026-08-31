package com.simonrowe.factory.logwatch.health;

import com.simonrowe.factory.logwatch.domain.SourceHealth;
import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether an empty read means "nothing is wrong" or "I cannot see".
 *
 * <p>Two tiers, checked in order, and the answer records which one produced it because they are
 * different qualities of evidence leading to different first moves:
 *
 * <ol>
 *   <li><strong>Alloy's component health.</strong> Direct — it reports the {@code 429} or
 *       {@code 401} verbatim, which distinguishes an exhausted quota from a rejected credential
 *       from a healthy-but-quiet stack. All three look identical from the query side.
 *   <li><strong>Container coverage.</strong> Used when Alloy cannot be reached. Inference from
 *       silence, so it needs a floor that does not fire on a genuinely quiet night.
 * </ol>
 *
 * <p>Pure: it takes observations and returns a verdict. Nothing here performs I/O.
 */
public final class SourceHealthChecker {

  /**
   * Windows shorter than this are not evidence of anything.
   *
   * <p>A five-minute post-deploy window over a stack that happens to be idle legitimately contains
   * no error or warning lines from any container. Applying the coverage floor to it would file a
   * source-health ticket after every quiet deploy, which teaches an operator to ignore exactly the
   * signal this module exists to raise.
   */
  private static final Duration MINIMUM_WINDOW_FOR_INFERENCE = Duration.ofHours(1);

  private SourceHealthChecker() {
  }

  /**
   * Reaches a verdict on the source.
   *
   * @param alloyWriteError the error Alloy reports for its {@code loki.write} component, empty
   *     when the component is healthy, or absent entirely when Alloy could not be reached
   * @param alloyReachable whether Alloy's component API answered at all
   * @param queryFailed whether the Loki query itself failed
   * @param distinctContainers how many distinct containers produced lines in the window
   * @param minimumContainers the configured floor below which coverage is judged too thin
   * @param window the scanned window
   * @return the verdict, with the evidence that produced it
   */
  public static SourceHealth check(
      final Optional<String> alloyWriteError,
      final boolean alloyReachable,
      final boolean queryFailed,
      final int distinctContainers,
      final int minimumContainers,
      final Duration window) {

    if (queryFailed) {
      return new SourceHealth(
          SourceHealth.Status.UNREACHABLE,
          SourceHealth.Tier.CONTAINER_COVERAGE,
          "The Loki query failed, so nothing about the stack's health can be concluded.");
    }

    // Tier 1. Preferred whenever Alloy answered, including when it answered "I am healthy":
    // a working write path is positive evidence that absence of lines means absence of errors.
    if (alloyReachable) {
      if (alloyWriteError.isPresent()) {
        return new SourceHealth(
            SourceHealth.Status.SILENT,
            SourceHealth.Tier.ALLOY_COMPONENT,
            "Alloy reports its loki.write component unhealthy: " + alloyWriteError.get());
      }
      return new SourceHealth(
          SourceHealth.Status.ALIVE,
          SourceHealth.Tier.ALLOY_COMPONENT,
          "Alloy reports its loki.write component healthy.");
    }

    // Tier 2. Inference from coverage, and only over a window long enough to expect lines.
    if (window.compareTo(MINIMUM_WINDOW_FOR_INFERENCE) < 0) {
      return new SourceHealth(
          SourceHealth.Status.ALIVE,
          SourceHealth.Tier.CONTAINER_COVERAGE,
          "Window shorter than "
              + MINIMUM_WINDOW_FOR_INFERENCE.toHours()
              + "h; too short to infer anything from quiet, so treated as alive.");
    }

    if (distinctContainers < minimumContainers) {
      return new SourceHealth(
          SourceHealth.Status.SILENT,
          SourceHealth.Tier.CONTAINER_COVERAGE,
          "Only "
              + distinctContainers
              + " distinct container(s) produced lines over "
              + window.toHours()
              + "h, below the floor of "
              + minimumContainers
              + ". Alloy's component API was unreachable, so this is inferred from silence.");
    }

    return new SourceHealth(
        SourceHealth.Status.ALIVE,
        SourceHealth.Tier.CONTAINER_COVERAGE,
        distinctContainers + " distinct containers produced lines in the window.");
  }
}
