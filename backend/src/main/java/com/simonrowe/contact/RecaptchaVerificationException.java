package com.simonrowe.contact;

public class RecaptchaVerificationException extends RuntimeException {

  public RecaptchaVerificationException(final String message) {
    super(message);
  }
}
