package com.simonrowe.factory.staticanalysis.domain;

/** What a {@link FindingGroup} reports. */
public enum FindingKind {
  /** A failing quality-gate condition, such as coverage on new code below the threshold. */
  QUALITY_GATE,
  /** Every open new-code issue raised by one rule in one module. */
  RULE
}
