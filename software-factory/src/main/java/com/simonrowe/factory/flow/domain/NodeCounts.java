package com.simonrowe.factory.flow.domain;

/**
 * What a node's badge counts.
 *
 * @param inFlight runs executing right now, unbounded by any time window
 * @param ok24h runs that completed successfully in the last 24 hours
 * @param failed24h runs that failed in the last 24 hours
 */
public record NodeCounts(int inFlight, int ok24h, int failed24h) {

  /**
   * Nothing has happened. Distinct from a null {@code NodeCounts}, which means "we do not know".
   */
  public static final NodeCounts NONE = new NodeCounts(0, 0, 0);
}
