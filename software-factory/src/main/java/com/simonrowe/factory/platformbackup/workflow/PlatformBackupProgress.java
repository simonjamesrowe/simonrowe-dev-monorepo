package com.simonrowe.factory.platformbackup.workflow;

/** Queryable state for a long-running platform capture. */
public record PlatformBackupProgress(String phase, String detail, boolean dryRun) {

  public static PlatformBackupProgress accepted() {
    return new PlatformBackupProgress("accepted", "Accepted", false);
  }
}
