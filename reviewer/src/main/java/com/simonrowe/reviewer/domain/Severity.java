package com.simonrowe.reviewer.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Finding severity intentionally has only three high-signal levels. */
public enum Severity {
  CRITICAL,
  WARNING,
  SUGGESTION;

  @JsonCreator
  public static Severity fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
