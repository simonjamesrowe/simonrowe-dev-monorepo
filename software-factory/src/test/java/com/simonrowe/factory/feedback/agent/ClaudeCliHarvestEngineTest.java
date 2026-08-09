package com.simonrowe.factory.feedback.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCliHarvestEngineTest {

  private static Lesson lesson(final String title, final String guidance) {
    return new Lesson(
        title, guidance, LessonScope.ORG_WIDE, List.of("https://c/1"),
        LessonSource.HUMAN, LessonConfidence.HIGH);
  }

  @Test
  void dropsBlankGuidanceAndCapsAtTen() {
    List<Lesson> raw = new ArrayList<>();
    raw.add(lesson("blank", "  "));
    for (int i = 0; i < 12; i++) {
      raw.add(lesson("lesson " + i, "Do the thing " + i + "."));
    }

    List<Lesson> lessons = ClaudeCliHarvestEngine.postProcess(raw);

    assertThat(lessons).hasSize(10);
    assertThat(lessons).noneMatch(item -> item.guidance().isBlank());
  }
}
