package com.simonrowe.dataops;

public enum OperationType {
  BACKUP,
  RESTORE,
  CLEAR,
  REBUILD_INDEX,
  REDEPLOY,
  REEMBED_CONTENT,
  /**
   * Captures the platform datastores — the four Postgres databases in
   * {@code langfuse-db} plus the ClickHouse {@code default} database — to a
   * separate Google Drive folder with its own retention window.
   *
   * <p>There is deliberately no matching restore constant: platform restore is a
   * host shell script ({@code scripts/restore-platform.sh}), because the scenario
   * that motivates it is a rebuilt host where this application is the thing being
   * rebuilt.
   */
  PLATFORM_BACKUP
}
