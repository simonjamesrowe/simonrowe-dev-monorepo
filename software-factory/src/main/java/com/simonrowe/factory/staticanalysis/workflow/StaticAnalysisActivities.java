package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Network and persistence operations for one static-analysis scan. */
@ActivityInterface
public interface StaticAnalysisActivities {

  /**
   * Checks the latest analysis is current, then reads and groups what it reports.
   *
   * @return the observation, including the analysis verdict
   */
  @ActivityMethod
  AnalysisObservation observe();

  /**
   * Persists one scan record.
   *
   * @param record the record to write
   */
  @ActivityMethod
  void recordRun(StaticAnalysisRunRecord record);
}
