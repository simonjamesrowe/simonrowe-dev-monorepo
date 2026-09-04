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
  IDLE
}
