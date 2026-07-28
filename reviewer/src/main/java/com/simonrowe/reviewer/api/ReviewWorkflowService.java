package com.simonrowe.reviewer.api;

import com.simonrowe.reviewer.config.ReviewerTaskQueues;
import com.simonrowe.reviewer.domain.ReviewProgress;
import com.simonrowe.reviewer.domain.ReviewRequest;
import com.simonrowe.reviewer.workflow.CodeReviewWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts and queries review workflows while keeping controllers Temporal-agnostic. */
@Service
public class ReviewWorkflowService {

  private final WorkflowClient workflowClient;

  public ReviewWorkflowService(final WorkflowClient workflowClient) {
    this.workflowClient = workflowClient;
  }

  public ReviewAccepted start(final ReviewRequest request) {
    String workflowId = workflowId(request);
    CodeReviewWorkflow workflow =
        workflowClient.newWorkflowStub(
            CodeReviewWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(ReviewerTaskQueues.REVIEWS)
                .setWorkflowId(workflowId)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::review, request);
      return new ReviewAccepted(workflowId, true);
    } catch (WorkflowExecutionAlreadyStarted exception) {
      return new ReviewAccepted(workflowId, false);
    }
  }

  public ReviewProgress progress(final String workflowId) {
    return workflowClient.newWorkflowStub(CodeReviewWorkflow.class, workflowId).progress();
  }

  private static String workflowId(final ReviewRequest request) {
    String revision =
        request.expectedHeadSha() == null || request.expectedHeadSha().isBlank()
            ? UUID.randomUUID().toString()
            : request.expectedHeadSha();
    return "code-review-"
        + safe(request.owner())
        + "-"
        + safe(request.repository())
        + "-"
        + request.pullNumber()
        + "-"
        + safe(revision);
  }

  private static String safe(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-");
  }
}
