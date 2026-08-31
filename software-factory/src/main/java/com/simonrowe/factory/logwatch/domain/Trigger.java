package com.simonrowe.factory.logwatch.domain;

/** What started a scan. Recorded so an operator can tell a post-deploy run from a nightly one. */
public enum Trigger {
  SCHEDULE,
  DEPLOY,
  MANUAL,
  DRY_RUN
}
