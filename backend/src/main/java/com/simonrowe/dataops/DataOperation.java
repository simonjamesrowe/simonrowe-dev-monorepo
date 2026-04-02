package com.simonrowe.dataops;

import java.time.Instant;

public record DataOperation(
    String id,
    OperationType type,
    OperationStatus status,
    Instant startedAt,
    Instant completedAt,
    String progressMessage,
    int progressPercent,
    String errorMessage,
    String resultSummary
) {

  public enum OperationStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
  }

  public static DataOperation start(final String id, final OperationType type) {
    return new DataOperation(
        id, type, OperationStatus.IN_PROGRESS,
        Instant.now(), null, "Starting...", 0, null, null
    );
  }

  public DataOperation withProgress(final String message, final int percent) {
    return new DataOperation(
        id, type, OperationStatus.IN_PROGRESS,
        startedAt, null, message, percent, null, null
    );
  }

  public DataOperation completed(final String summary) {
    return new DataOperation(
        id, type, OperationStatus.COMPLETED,
        startedAt, Instant.now(), "Completed", 100, null, summary
    );
  }

  public DataOperation failed(final String error) {
    return new DataOperation(
        id, type, OperationStatus.FAILED,
        startedAt, Instant.now(), "Failed", progressPercent, error, null
    );
  }
}
