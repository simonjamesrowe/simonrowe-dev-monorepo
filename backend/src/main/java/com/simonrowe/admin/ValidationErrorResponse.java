package com.simonrowe.admin;

import java.time.Instant;
import java.util.List;

public record ValidationErrorResponse(
    int status,
    String error,
    String message,
    List<FieldError> fieldErrors,
    Instant timestamp
) {

  public record FieldError(
      String field,
      String message
  ) {
  }
}
