package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;

/** Network and persistence operations for one log-watch scan. */
@ActivityInterface
public interface LogWatchActivities {

  /**
   * Checks the source is alive, reads the window, and groups what it finds.
   *
   * @param from window start
   * @param to window end
   * @return the observation, including the source-health verdict
   */
  @ActivityMethod
  ScanObservation observe(Instant from, Instant to);

  /**
   * Persists one scan record.
   *
   * @param record the record to write
   */
  @ActivityMethod
  void recordRun(LogWatchRunRecord record);
}
