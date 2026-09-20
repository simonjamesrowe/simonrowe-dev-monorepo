package com.simonrowe.coparent.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Stable, non-enumerating error responses for CoParent controllers. */
@RestControllerAdvice(basePackages = "com.simonrowe.coparent")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoparentExceptionHandler {

  /** Converts validation errors into the checked-in contract shape. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(final MethodArgumentNotValidException exception) {
    final Map<String, String> fields = new LinkedHashMap<>();
    exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(
        error.getField(), error.getDefaultMessage()));
    return ResponseEntity.badRequest().body(Map.of(
        "code", "validation_failed",
        "message", "The request could not be completed",
        "fieldErrors", fields));
  }

  /** Keeps deliberate client errors compact and free of internal details. */
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(final ResponseStatusException exception) {
    final String message = exception.getReason() == null
        ? exception.getStatusCode().toString()
        : exception.getReason();
    final String code = switch (exception.getStatusCode().value()) {
      case 400 -> "invalid_request";
      case 403 -> "forbidden";
      case 404 -> "not_found";
      default -> "request_failed";
    };
    return ResponseEntity.status(exception.getStatusCode())
        .body(Map.of("code", code, "message", message));
  }
}
