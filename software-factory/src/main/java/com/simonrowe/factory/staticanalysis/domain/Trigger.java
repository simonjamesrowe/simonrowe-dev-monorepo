package com.simonrowe.factory.staticanalysis.domain;

/** What started a static-analysis scan. */
public enum Trigger {
  SCHEDULED,
  MANUAL,
  DRY_RUN
}
