package com.simonrowe.contact;

public class EmailDeliveryException extends RuntimeException {

  public EmailDeliveryException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
