package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.domain.ReviewProgress;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ReviewResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Durable orchestration contract for one pull-request revision. */
@WorkflowInterface
public interface CodeReviewWorkflow {

  @WorkflowMethod
  ReviewResult review(ReviewRequest request);

  @QueryMethod
  ReviewProgress progress();
}
