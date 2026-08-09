package com.simonrowe.factory.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Source of a lesson (human guidance, AI reviewer insight, or both). */
public enum LessonSource {
  HUMAN,
  REVIEWER,
  BOTH;

  @JsonCreator
  public static LessonSource fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
