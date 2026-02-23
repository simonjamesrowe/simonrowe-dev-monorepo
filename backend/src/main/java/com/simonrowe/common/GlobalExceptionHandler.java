package com.simonrowe.common;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ConstraintViolationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public void handleConstraintViolation() {
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ErrorResponseDto> handleNotFound(
      ResourceNotFoundException ex
  ) {
    ErrorResponseDto error = new ErrorResponseDto(
        ex.getMessage(),
        HttpStatus.NOT_FOUND.value(),
        Instant.now()
    );
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponseDto> handleResponseStatus(
      ResponseStatusException ex
  ) {
    ErrorResponseDto error = new ErrorResponseDto(
        ex.getReason(),
        ex.getStatusCode().value(),
        Instant.now()
    );
    return ResponseEntity.status(ex.getStatusCode()).body(error);
  }

}
