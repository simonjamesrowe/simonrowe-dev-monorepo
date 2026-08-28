package com.simonrowe.factory.platformbackup.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.QueryMethod;

/** Captures the platform datastores and uploads the archive to Google Drive. */
@WorkflowInterface
public interface PlatformBackupWorkflow {

  /**
   * Runs one capture.
   *
   * @param dryRun whether the script should only print what it would do
   * @return the script's trailing output
   */
  @WorkflowMethod
  String backup(boolean dryRun);

  /** Current operator-facing progress. */
  @QueryMethod
  PlatformBackupProgress progress();
}
