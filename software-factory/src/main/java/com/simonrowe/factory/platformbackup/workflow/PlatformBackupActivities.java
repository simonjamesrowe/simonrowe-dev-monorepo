package com.simonrowe.factory.platformbackup.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** The single side-effecting step of the platform backup. */
@ActivityInterface
public interface PlatformBackupActivities {

  /**
   * Runs {@code scripts/backup-platform.sh} to completion.
   *
   * @param dryRun whether to pass {@code --dry-run}, which makes the script print what it would do
   *     and change nothing
   * @return the script's trailing output, for the workflow result and the Temporal UI
   */
  @ActivityMethod
  String capture(boolean dryRun);
}
