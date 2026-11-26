package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.domain.GateRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Works out the {@code Code Review} check again after a person resolved one of this reviewer's
 * threads, without running a review.
 *
 * <p>Brunel's equivalent ("a Resolve click recomputes the gate instead of re-reviewing") exists
 * for the same reason: declining a critical finding by resolving its thread should unblock the
 * merge then, not on the next push, and should not spend a model run to do it.
 */
@WorkflowInterface
public interface ReviewGateWorkflow {

  @WorkflowMethod
  String recompute(GateRequest request);
}
