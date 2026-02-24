package com.simonrowe.contact;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ContactController.class)
public class ContactExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(
      final MethodArgumentNotValidException ex
  ) {
    final List<Map<String, String>> errors = ex.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
        .toList();
    return ResponseEntity.badRequest().body(Map.of("errors", errors));
  }

  @ExceptionHandler(RecaptchaVerificationException.class)
  public ResponseEntity<Map<String, Object>> handleRecaptchaFailure(
      final RecaptchaVerificationException ex
  ) {
    final List<Map<String, String>> errors = List.of(
        Map.of("field", "recaptchaToken", "message", ex.getMessage())
    );
    return ResponseEntity.badRequest().body(Map.of("errors", errors));
  }

  @ExceptionHandler(RecaptchaServiceUnavailableException.class)
  public ResponseEntity<Map<String, String>> handleRecaptchaUnavailable(
      final RecaptchaServiceUnavailableException ex
  ) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(EmailDeliveryException.class)
  public ResponseEntity<Map<String, String>> handleEmailDelivery(
      final EmailDeliveryException ex
  ) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", ex.getMessage()));
  }
}
