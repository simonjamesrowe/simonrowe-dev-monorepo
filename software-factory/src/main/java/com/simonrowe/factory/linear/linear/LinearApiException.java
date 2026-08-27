package com.simonrowe.factory.linear.linear;

/**
 * A Linear API fault, carrying whether retrying could help.
 *
 * <p>The distinction is load-bearing: the Temporal activity's retry policy is driven by it, and a
 * revoked or read-only key must fail fast rather than consume a retry budget.
 */
public class LinearApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean retryable;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param retryable whether a later attempt could succeed
   */
  public LinearApiException(final String message, final boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what failed
   * @param retryable whether a later attempt could succeed
   * @param cause the underlying fault
   */
  public LinearApiException(
      final String message, final boolean retryable, final Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /**
   * Whether a later attempt could succeed.
   *
   * @return true for transport faults, 5xx and 429; false for auth and query errors
   */
  public boolean retryable() {
    return retryable;
  }
}
