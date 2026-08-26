package com.simonrowe.factory.deploy.workflow;

import com.simonrowe.factory.deploy.domain.DeployProgress;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.domain.DeployResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Orchestrates one production deploy: sync the host configuration, put the maintenance page up,
 * pull and recreate, verify, take the page down, verify the public hostnames — and on a
 * verification failure, restore the previous version, verify that, diagnose the failure and
 * report it.
 *
 * <p>Started with signal-with-start on the fixed workflow id {@code deploy-prod}, which is what
 * makes duplicate webhook deliveries idempotent and two merges minutes apart produce one deploy of
 * the newer commit rather than two overlapping {@code docker compose up -d} runs.
 */
@WorkflowInterface
public interface DeployWorkflow {

  /** The fixed workflow id. One deploy at a time, on a single-node host. */
  String WORKFLOW_ID = "deploy-prod";

  /**
   * Runs the deploy.
   *
   * @param request the commit to deploy and the switches in force
   * @return how the deploy ended
   */
  @WorkflowMethod
  DeployResult run(DeployRequest request);

  /**
   * Records that a (possibly newer) commit wants deploying.
   *
   * <p>Sent by signal-with-start alongside {@link #run}, so the first delivery starts the workflow
   * and every later one signals the running instance. {@link #run} re-reads this after each
   * attempt and deploys again if a newer commit arrived mid-deploy — without that, a merge landing
   * during a deploy would signal a workflow that never looks at the field again, and its commit
   * would never deploy at all.
   *
   * @param sha the commit to deploy
   */
  @SignalMethod
  void deployRequested(String sha);

  /**
   * Reports where the deploy has got to, for an operator watching it in the Temporal UI.
   *
   * @return the current progress snapshot
   */
  @QueryMethod
  DeployProgress progress();
}
