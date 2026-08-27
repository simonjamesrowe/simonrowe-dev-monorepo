package com.simonrowe.platform;

import java.time.Instant;

/**
 * The version facts for one first-party service.
 *
 * <p>Every field except {@code name} and {@code reachable} may be absent: a service built
 * outside a git checkout has no commit, and a service the backend cannot reach reports
 * nothing at all. The page renders absence rather than erroring, so null is a supported
 * value here rather than a defect.
 *
 * @param name the compose service name, e.g. {@code backend}
 * @param commit the full commit SHA, or {@code unknown}
 * @param shortCommit the seven-character SHA, or {@code dev}
 * @param commitSubject the commit subject line, or null
 * @param commitTime when the commit was authored, or null when unknown
 * @param startedAt when the process started, or null when not reported
 * @param reachable false when the backend could not reach the service to ask
 */
public record ServiceVersion(
    String name,
    String commit,
    String shortCommit,
    String commitSubject,
    Instant commitTime,
    Instant startedAt,
    boolean reachable) {

  static final String UNKNOWN_COMMIT = "unknown";
  static final String DEV_SHORT_COMMIT = "dev";

  /**
   * A service that could not be reached.
   *
   * @param name the compose service name
   * @return a version reporting nothing but the name
   */
  public static ServiceVersion unreachable(final String name) {
    return new ServiceVersion(name, UNKNOWN_COMMIT, DEV_SHORT_COMMIT, null, null, null, false);
  }
}
