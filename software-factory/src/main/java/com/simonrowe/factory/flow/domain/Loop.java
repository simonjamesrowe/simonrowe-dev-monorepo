package com.simonrowe.factory.flow.domain;

/**
 * Which of the factory's three feedback loops an edge belongs to.
 *
 * <p>Drawn at different weights so they can be told apart at a glance. The slow loop is the one no
 * existing view shows, and is why this graph is a ring rather than a pipeline.
 */
public enum Loop {
  /** Minutes. Pull request against code review. */
  FAST,
  /** Hours. Linear to build to merge to deploy to production and back to Linear. */
  MAIN,
  /** Days. A closed review shapes the agents through agent-setup. */
  SLOW
}
