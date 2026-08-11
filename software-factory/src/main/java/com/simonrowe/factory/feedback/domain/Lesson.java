package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Guidance principle extracted or reinforced from PR review conversation. */
public record Lesson(
    String title, String guidance, LessonScope scope, List<String> evidence,
    LessonSource source, LessonConfidence confidence) {

  public Lesson {
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
  }
}
