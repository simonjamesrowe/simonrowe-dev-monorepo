package com.simonrowe.contact;

public class RecaptchaServiceUnavailableException extends RuntimeException {

  public RecaptchaServiceUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
