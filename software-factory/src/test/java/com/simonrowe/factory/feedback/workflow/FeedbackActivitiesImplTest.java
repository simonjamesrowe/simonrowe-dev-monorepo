package com.simonrowe.factory.feedback.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackActivitiesImplTest {

  private static Lesson lesson(final LessonScope scope) {
    return new Lesson(
        "t", "g", scope, List.of("https://c/1"), LessonSource.HUMAN, LessonConfidence.HIGH);
  }

  private static final FeedbackRequest REQUEST =
      new FeedbackRequest("simonjamesrowe", "simonrowe-dev-monorepo", 42, 999L, false);

  @Test
  void orgWideLessonsTargetOnlyAgentSetup() {
    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            REQUEST, List.of(lesson(LessonScope.ORG_WIDE)), "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(1);
    assertThat(targets.getFirst().repository()).isEqualTo("agent-setup");
  }

  @Test
  void repoSpecificLessonsAddTheSourceRepoWithClaudeMdOnly() {
    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            REQUEST,
            List.of(lesson(LessonScope.ORG_WIDE), lesson(LessonScope.REPO_SPECIFIC)),
            "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(2);
    assertThat(targets.get(1).repository()).isEqualTo("simonrowe-dev-monorepo");
    assertThat(targets.get(1).allowedPaths()).containsExactly("CLAUDE.md");
    assertThat(targets.get(1).lessons()).hasSize(1);
  }

  @Test
  void agentSetupAsTheSourceRepoIsNotTargetedTwice() {
    FeedbackRequest request =
        new FeedbackRequest("simonjamesrowe", "agent-setup", 7, 999L, false);

    var targets =
        FeedbackActivitiesImpl.resolveTargets(
            request, List.of(lesson(LessonScope.REPO_SPECIFIC)), "simonjamesrowe/agent-setup");

    assertThat(targets).hasSize(1);
  }
}
