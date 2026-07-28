package com.simonrowe.reviewer.workflow;

import com.simonrowe.reviewer.domain.ReviewProgress;
import com.simonrowe.reviewer.domain.ReviewRequest;
import com.simonrowe.reviewer.domain.ReviewResult;
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
