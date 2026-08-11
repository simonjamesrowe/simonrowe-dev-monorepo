package com.simonrowe.narration;

public interface NarrationProvider {

  enum FailureKind {
    SAFE_TO_RETRY,
    AMBIGUOUS,
    UNAVAILABLE
  }

  record StartResult(String operationName) {
  }

  record OperationResult(boolean done, boolean succeeded, String failureCode) {

    public static OperationResult pending() {
      return new OperationResult(false, false, null);
    }

    public static OperationResult success() {
      return new OperationResult(true, true, null);
    }

    public static OperationResult failure(final String code) {
      return new OperationResult(true, false, code);
    }
  }

  class NarrationProviderException extends RuntimeException {

    private final FailureKind kind;

    public NarrationProviderException(
        final String message,
        final FailureKind kind,
        final Throwable cause
    ) {
      super(message, cause);
      this.kind = kind;
    }

    public FailureKind kind() {
      return kind;
    }
  }

  boolean isConfigured();

  StartResult start(String script, String outputObject);

  OperationResult poll(String operationName);

  byte[] download(String outputObject);
}
