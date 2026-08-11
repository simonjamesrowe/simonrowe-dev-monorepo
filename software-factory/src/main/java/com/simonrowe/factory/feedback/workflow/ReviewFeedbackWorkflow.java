package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.FeedbackResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Orchestrates the review feedback pipeline: fetch, harvest, log, and distill guidance. */
@WorkflowInterface
public interface ReviewFeedbackWorkflow {

  @WorkflowMethod
  FeedbackResult harvest(FeedbackRequest request);

  @QueryMethod
  FeedbackProgress progress();
}
