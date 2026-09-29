package com.simonrowe.homepage;

import com.simonrowe.admin.ValidationErrorResponse;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Returns the field errors themselves, not just a joined message, so the editor can show
 * each one beside its input. {@code message} still carries the joined text for any client
 * that reads only that, as the other admin endpoints' errors do.
 */
@RestControllerAdvice(assignableTypes = HomePageAdminController.class)
class HomePageExceptionHandler {

  @ExceptionHandler(HomePageValidationException.class)
  ResponseEntity<ValidationErrorResponse> handleValidation(final HomePageValidationException ex) {
    return ResponseEntity.badRequest().body(new ValidationErrorResponse(
        HttpStatus.BAD_REQUEST.value(),
        HttpStatus.BAD_REQUEST.getReasonPhrase(),
        ex.getMessage(),
        ex.fieldErrors(),
        Instant.now()));
  }
}
