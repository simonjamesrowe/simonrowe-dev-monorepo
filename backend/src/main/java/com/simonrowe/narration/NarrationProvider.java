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

  /**
   * Largest script, in UTF-8 bytes, that {@link #synthesizeImmediately(String)} accepts.
   *
   * @return the limit, or 0 when the provider has no synchronous path
   */
  int maxImmediateBytes();

  /**
   * Synthesises a short script synchronously and returns the finished MP3 bytes.
   *
   * <p>Google's long-audio endpoint currently rejects MP3 ("only LINEAR16 audio encodings
   * are supported for Long Audio Synthesis"), while the ordinary synthesis endpoint still
   * produces MP3 directly — it just caps input at {@link #maxImmediateBytes()}. Scripts
   * that fit therefore skip the operation/poll/GCS round trip entirely.
   *
   * @param script the prose to synthesise, within {@link #maxImmediateBytes()}
   * @return the MP3 bytes
   * @throws NarrationProviderException when the provider rejects or cannot be reached
   */
  byte[] synthesizeImmediately(String script);

  StartResult start(String script, String outputObject);

  OperationResult poll(String operationName);

  byte[] download(String outputObject);
}
