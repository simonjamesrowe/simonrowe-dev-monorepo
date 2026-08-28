package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** Temporal activities wiring conversation fetch, lesson harvesting, and guidance distillation. */
@ActivityInterface
public interface FeedbackActivities {

  @ActivityMethod
  ReviewConversation fetchConversation(FeedbackRequest request);

  @ActivityMethod
  List<Lesson> harvestLessons(FeedbackRequest request, ReviewConversation conversation);

  @ActivityMethod
  void recordLearnings(
      FeedbackRequest request, ReviewConversation conversation, List<Lesson> lessons,
      String workflowId, DistillationStatus initialStatus);

  @ActivityMethod
  DistillationOutcome distillAndPropose(
      FeedbackRequest request, List<Lesson> lessons, String linearIssueUrl);

  @ActivityMethod
  void recordLinearIssue(FeedbackRequest request, String issueIdentifier, String issueUrl);

  @ActivityMethod
  void recordDistillation(FeedbackRequest request, DistillationOutcome outcome);
}
