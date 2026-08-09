package com.simonrowe.factory.feedback.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.ConversationComment;
import com.simonrowe.factory.feedback.domain.ConversationThread;
import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackPhase;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.FeedbackResult;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.LessonConfidence;
import com.simonrowe.factory.feedback.domain.LessonScope;
import com.simonrowe.factory.feedback.domain.LessonSource;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFeedbackWorkflowTest {

  private static final FeedbackRequest REQUEST =
      new FeedbackRequest("example", "project", 42, 999L, false);

  private static ReviewConversation conversation(final boolean humanSignal) {
    List<ConversationThread> threads =
        humanSignal
            ? List.of(
                new ConversationThread(
                    true,
                    List.of(
                        new ConversationComment(
                            "simon", false, "please pin versions", null, null, null,
                            "https://c/1"))))
            : List.of();
    return new ReviewConversation(
        "Title", "https://pr/42", "author", true, List.of(), threads, List.of());
  }

  private static Lesson lesson() {
    return new Lesson(
        "Pin images", "Always pin container image versions.", LessonScope.ORG_WIDE,
        List.of("https://c/1"), LessonSource.HUMAN, LessonConfidence.HIGH);
  }

  private FeedbackResult run(final FakeActivities activities, final FeedbackRequest request) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(FeedbackTaskQueues.REVIEW_FEEDBACK);
      worker.registerWorkflowImplementationTypes(ReviewFeedbackWorkflowImpl.class);
      worker.registerActivitiesImplementations(activities);
      environment.start();
      ReviewFeedbackWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  ReviewFeedbackWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(FeedbackTaskQueues.REVIEW_FEEDBACK)
                      .setWorkflowId("feedback-test")
                      .build());
      return workflow.harvest(request);
    }
  }

  @Test
  void harvestsLogsAndDistillsWhenHumansEngaged() {
    FakeActivities activities =
        new FakeActivities(
            conversation(true),
            List.of(lesson()),
            new DistillationOutcome(
                DistillationStatus.PROPOSED, List.of("https://pr/feedback/1"), null));

    FeedbackResult result = run(activities, REQUEST);

    assertThat(result.lessonCount()).isEqualTo(1);
    assertThat(result.distillationStatus()).isEqualTo(DistillationStatus.PROPOSED);
    assertThat(result.proposalUrls()).containsExactly("https://pr/feedback/1");
    assertThat(activities.recordedStatuses)
        .containsExactly(DistillationStatus.SKIPPED_NO_LESSONS);
    assertThat(activities.recordedOutcomes).hasSize(1);
  }

  @Test
  void exitsEarlyRecordingNoSignalWhenOnlyTheBotSpoke() {
    FakeActivities activities = new FakeActivities(conversation(false), List.of(), null);

    FeedbackResult result = run(activities, REQUEST);

    assertThat(result.lessonCount()).isZero();
    assertThat(result.distillationStatus()).isEqualTo(DistillationStatus.NO_SIGNAL);
    assertThat(activities.recordedStatuses).containsExactly(DistillationStatus.NO_SIGNAL);
    assertThat(activities.harvested).isFalse();
    assertThat(activities.distilled).isFalse();
  }

  @Test
  void dryRunHarvestsAndLogsButNeverDistills() {
    FakeActivities activities = new FakeActivities(conversation(true), List.of(lesson()), null);

    FeedbackResult result =
        run(activities, new FeedbackRequest("example", "project", 42, 999L, true));

    assertThat(result.distillationStatus()).isEqualTo(DistillationStatus.DRY_RUN);
    assertThat(activities.recordedStatuses).containsExactly(DistillationStatus.DRY_RUN);
    assertThat(activities.distilled).isFalse();
  }

  @Test
  void zeroLessonsSkipsDistillation() {
    FakeActivities activities = new FakeActivities(conversation(true), List.of(), null);

    FeedbackResult result = run(activities, REQUEST);

    assertThat(result.distillationStatus()).isEqualTo(DistillationStatus.SKIPPED_NO_LESSONS);
    assertThat(activities.distilled).isFalse();
  }

  @Test
  void distillationFailureIsRecordedAsFailedAndTheWorkflowStillFails() {
    RuntimeException distillFailure = new RuntimeException("distill boom");
    FakeActivities activities =
        new FakeActivities(conversation(true), List.of(lesson()), null, distillFailure);

    assertThatThrownBy(() -> run(activities, REQUEST))
        .isInstanceOf(WorkflowFailedException.class);

    assertThat(activities.recordedOutcomes).hasSize(1);
    assertThat(activities.recordedOutcomes.getFirst().status())
        .isEqualTo(DistillationStatus.FAILED);
  }

  private static final class FakeActivities implements FeedbackActivities {
    private final ReviewConversation conversation;
    private final List<Lesson> lessons;
    private final DistillationOutcome outcome;
    private final RuntimeException distillFailure;
    final List<DistillationStatus> recordedStatuses = new ArrayList<>();
    final List<DistillationOutcome> recordedOutcomes = new ArrayList<>();
    boolean harvested;
    boolean distilled;

    FakeActivities(
        final ReviewConversation conversation, final List<Lesson> lessons,
        final DistillationOutcome outcome) {
      this(conversation, lessons, outcome, null);
    }

    FakeActivities(
        final ReviewConversation conversation, final List<Lesson> lessons,
        final DistillationOutcome outcome, final RuntimeException distillFailure) {
      this.conversation = conversation;
      this.lessons = lessons;
      this.outcome = outcome;
      this.distillFailure = distillFailure;
    }

    @Override
    public ReviewConversation fetchConversation(final FeedbackRequest request) {
      return conversation;
    }

    @Override
    public List<Lesson> harvestLessons(
        final FeedbackRequest request, final ReviewConversation reviewConversation) {
      harvested = true;
      return lessons;
    }

    @Override
    public void recordLearnings(
        final FeedbackRequest request, final ReviewConversation reviewConversation,
        final List<Lesson> lessonList, final String workflowId,
        final DistillationStatus initialStatus) {
      recordedStatuses.add(initialStatus);
    }

    @Override
    public DistillationOutcome distillAndPropose(
        final FeedbackRequest request, final List<Lesson> lessonList) {
      distilled = true;
      if (distillFailure != null) {
        throw distillFailure;
      }
      return outcome;
    }

    @Override
    public void recordDistillation(
        final FeedbackRequest request, final DistillationOutcome distillationOutcome) {
      recordedOutcomes.add(distillationOutcome);
    }
  }
}
