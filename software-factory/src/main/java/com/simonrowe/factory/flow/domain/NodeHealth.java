package com.simonrowe.factory.flow.domain;

/**
 * What a node's badge says.
 *
 * <p>{@link #IDLE} and {@link #OFFLINE} are separate on purpose: "nothing to do" and "nothing is
 * listening" lead an operator to different actions, and conflating them is the same mistake as
 * reporting an unreadable log source as a clean scan.
 */
public enum NodeHealth {
  /** Configured, polled, prerequisites met. */
  READY,
  /** Enabled but not usable — a missing prerequisite or a missing poller. */
  DEGRADED,
  /** Switched off by configuration. */
  DISABLED,
  /** The owning container could not be asked. */
  UNAVAILABLE,
  /** Work is waiting and nothing has picked it up. */
  OFFLINE,
  /** Nothing to do. */
  IDLE,
  /**
   * This node's state is not reported here. It has no owning module and no artifact source this
   * container can read — {@code production} is the current example. An unconditional {@link
   * #READY} on such a node would be a false statement of health, most misleading during exactly
   * the incident someone would open this page for. Production's real state lives on the platform
   * status endpoint ({@code GET /api/platform/status}), which this graph does not duplicate.
   */
  NOT_TRACKED
}
