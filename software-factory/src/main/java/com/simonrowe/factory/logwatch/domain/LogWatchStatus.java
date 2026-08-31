package com.simonrowe.factory.logwatch.domain;

/**
 * How a scan ended.
 *
 * <p>{@code NO_FINDINGS} and {@code SOURCE_UNHEALTHY} are deliberately separate. Collapsing them
 * into one "nothing to report" status is the exact failure this module exists to prevent: between
 * 10 and 31 August 2026 Grafana Cloud accepted no logs at all, and a scan of that window would
 * have found nothing and reported a healthy system with complete confidence.
 */
public enum LogWatchStatus {
  RUNNING,
  COMPLETED,
  NO_FINDINGS,
  SOURCE_UNHEALTHY,
  FAILED
}
