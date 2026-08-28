package com.simonrowe.factory.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Stage of feedback workflow progress. */
public enum FeedbackPhase {
  ACCEPTED,
  FETCHING,
  HARVESTING,
  LOGGING,
  FILING,
  DISTILLING,
  COMPLETED,
  NO_SIGNAL,
  FAILED;

  @JsonCreator
  public static FeedbackPhase fromJson(final String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }

  @JsonValue
  public String toJson() {
    return name().toLowerCase(Locale.ROOT);
  }
}
