package com.simonrowe.reviewer.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Overall review disposition; publishing is advisory in the first release. */
public enum Verdict {
  APPROVE,
  COMMENT,
  REQUEST_CHANGES;

  @JsonCreator
  public static Verdict fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
