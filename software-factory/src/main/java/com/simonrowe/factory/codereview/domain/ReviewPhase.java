package com.simonrowe.factory.codereview.domain;

/** Coarse phases exposed by the Temporal query API. */
public enum ReviewPhase {
  ACCEPTED,
  LOADING_PULL_REQUEST,
  REVIEWING,
  PUBLISHING,
  COMPLETED,
  FAILED
}
