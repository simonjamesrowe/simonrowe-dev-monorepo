package com.simonrowe.factory.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Confidence level in the validity of a lesson extracted from conversation. */
public enum LessonConfidence {
  HIGH,
  MEDIUM,
  LOW;

  @JsonCreator
  public static LessonConfidence fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
