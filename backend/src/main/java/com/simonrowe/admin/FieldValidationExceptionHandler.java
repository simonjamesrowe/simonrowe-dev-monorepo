package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Returns the field errors themselves, not just a joined message. {@code message} still carries
 * the joined text for a client that reads only that, as the other admin endpoints' errors do.
 */
@RestControllerAdvice
public class FieldValidationExceptionHandler {

  @ExceptionHandler(FieldValidationException.class)
  public ResponseEntity<ValidationErrorResponse> handle(final FieldValidationException ex) {
    return ResponseEntity.badRequest().body(new ValidationErrorResponse(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        ex.getMessage(),
        ex.fieldErrors(),
        Instant.now()));
  }
}
