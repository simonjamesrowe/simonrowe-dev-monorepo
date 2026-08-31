package com.simonrowe.factory.logwatch.loki;

/** Raised when Loki cannot be read: a transport failure, or any non-2xx status. */
public class LokiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message.
   *
   * @param message what went wrong
   */
  public LokiException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and cause.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public LokiException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
