package com.simonrowe.factory.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Outcome of attempted distillation from lessons to guidance proposals. */
public enum DistillationStatus {
  SKIPPED_NO_LESSONS,
  PROPOSED,
  NO_CHANGE,
  FAILED,
  DRY_RUN,
  NO_SIGNAL;

  @JsonCreator
  public static DistillationStatus fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
