package com.simonrowe.factory.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Scope of applicability for a lesson (organization-wide or repo-specific). */
public enum LessonScope {
  ORG_WIDE,
  REPO_SPECIFIC;

  @JsonCreator
  public static LessonScope fromJson(final String value) {
    return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().replace('_', '-').toLowerCase(Locale.ROOT);
  }
}
