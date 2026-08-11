package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Orchestrates one scheduled CVE-fix run: read Dependency-Track findings, have the agent bump the
 * vulnerable dependencies, open one pull request, then poll CI and feed failures back to the agent
 * until it is green or the repair budget runs out.
 */
@WorkflowInterface
public interface CveFixWorkflow {

  /**
   * Runs the whole CVE-fix flow.
   *
   * @param request the run's settings, including the CI poll interval, repair budget and cap
   * @return how the run ended, including the pull request it left behind
   */
  @WorkflowMethod
  CveFixResult run(CveFixRequest request);

  /**
   * Reports where the run has got to, for an operator watching a long CI loop.
   *
   * @return the current progress snapshot
   */
  @QueryMethod
  CveFixProgress progress();
}
