package com.simonrowe.dataops;

public record DataOperationsStatus(
    boolean googleDriveConnected,
    String googleDriveError,
    boolean operationInProgress,
    DataOperation currentOperation,
    DataOperation lastOperation
) {
}
