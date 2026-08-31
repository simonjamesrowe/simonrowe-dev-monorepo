package com.simonrowe.factory.feedback.agent;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCliDistillEngineTest {

  @Test
  void promptEnumeratesAllowedPathsAndLessons() throws Exception {
    DistillTarget target =
        new DistillTarget(
            "simonjamesrowe", "agent-setup", Path.of("/tmp/ws"),
            List.of("components/instructions/global.md", "components/skills/**"),
            "the org-wide agent guidance package");
    Lesson lesson =
        new Lesson(
            "Pin images", "Always pin container image versions.", LessonScope.ORG_WIDE,
            List.of("https://c/1"), LessonSource.HUMAN, LessonConfidence.HIGH);

    String prompt = ClaudeCliDistillEngine.prompt(target, List.of(lesson), new ObjectMapper());

    assertThat(prompt).contains("components/instructions/global.md");
    assertThat(prompt).contains("components/skills/**");
    assertThat(prompt).contains("Always pin container image versions.");
    assertThat(prompt).contains("simonjamesrowe/agent-setup");
  }
}
