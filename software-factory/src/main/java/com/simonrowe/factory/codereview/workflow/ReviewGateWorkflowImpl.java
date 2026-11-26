package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.GateRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** One activity, retried like every other GitHub call the reviewer makes. */
@WorkflowImpl(taskQueues = CodeReviewTaskQueues.REVIEWS)
public class ReviewGateWorkflowImpl implements ReviewGateWorkflow {

  private final ReviewActivities activities =
      Workflow.newActivityStub(
          ReviewActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(10))
                      .setMaximumAttempts(3)
                      .build())
              .build());

  @Override
  public String recompute(final GateRequest request) {
    return activities.recomputeGate(request);
  }
}
