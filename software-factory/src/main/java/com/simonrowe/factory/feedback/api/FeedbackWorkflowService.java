package com.simonrowe.factory.feedback.api;

import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.workflow.ReviewFeedbackWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Service;

/** Starts and queries review feedback workflows while keeping controllers Temporal-agnostic. */
@Service
public class FeedbackWorkflowService {

  private final WorkflowClient workflowClient;

  public FeedbackWorkflowService(final WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  public FeedbackAccepted start(final FeedbackRequest request) {
    String workflowId = workflowId(request);
    ReviewFeedbackWorkflow workflow =
        workflowClient.newWorkflowStub(
            ReviewFeedbackWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(FeedbackTaskQueues.REVIEW_FEEDBACK)
                .setWorkflowId(workflowId)
                // The workflow id deliberately excludes the head SHA so a reopened-then-reclosed
                // PR doesn't re-harvest under the same id — that guard must stay. But
                // REJECT_DUPLICATE also rejects restarting a FAILED execution, which is exactly
                // the runbook's documented manual re-drive path ("Distillation FAILED ... re-drive
                // with the manual endpoint"). ALLOW_DUPLICATE_FAILED_ONLY permits restarting the
                // same id after a genuine failure while still rejecting a start against an id that
                // already COMPLETED successfully.
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .build());
    try {
      WorkflowClient.start(workflow::harvest, request);
      return new FeedbackAccepted(workflowId, true);
    } catch (WorkflowExecutionAlreadyStarted exception) {
      return new FeedbackAccepted(workflowId, false);
    }
  }

  public FeedbackProgress progress(final String workflowId) {
    return workflowClient.newWorkflowStub(ReviewFeedbackWorkflow.class, workflowId).progress();
  }

  private static String workflowId(final FeedbackRequest request) {
    return "review-feedback-"
        + safe(request.owner())
        + "-"
        + safe(request.repository())
        + "-"
        + request.pullNumber();
  }

  private static String safe(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-");
  }
}
