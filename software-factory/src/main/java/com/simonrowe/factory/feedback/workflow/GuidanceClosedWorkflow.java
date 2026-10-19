package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.feedback.domain.GuidanceClosedRequest;
import com.simonrowe.factory.feedback.domain.GuidanceClosedResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Closes a feedback ticket once every guidance pull request it produced has merged.
 *
 * <p>The other half of {@link ReviewFeedbackWorkflow}. That one never runs for a guidance pull
 * request, because harvesting the distiller's own pull requests would teach it from itself; this
 * one runs only for them.
 */
@WorkflowInterface
public interface GuidanceClosedWorkflow {

  @WorkflowMethod
  GuidanceClosedResult close(GuidanceClosedRequest request);
}
