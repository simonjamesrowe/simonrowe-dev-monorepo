package com.simonrowe.factory.flow.domain;

/** The horizontal band a node is drawn in. */
public enum Band {
  OBSERVE,
  PLAN,
  BUILD,
  SHIP,
  LEARN,
  /** Off the ring entirely. Only platform backup. */
  UTILITY
}
