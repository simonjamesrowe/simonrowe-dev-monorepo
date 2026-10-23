package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisProgress;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One scan of SonarCloud's latest analysis of {@code main}: check it is current, file each
 * finding group in Linear, and close the tickets for findings it no longer reports.
 */
@WorkflowInterface
public interface StaticAnalysisWorkflow {

  /**
   * Runs the scan.
   *
   * @param request the scan's settings
   * @return how it ended
   */
  @WorkflowMethod
  StaticAnalysisResult run(StaticAnalysisRequest request);

  /**
   * Reports where the scan has got to.
   *
   * @return the current progress snapshot
   */
  @QueryMethod
  StaticAnalysisProgress progress();
}
