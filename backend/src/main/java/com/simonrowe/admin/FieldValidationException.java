package com.simonrowe.admin;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import java.util.List;

/**
 * A save refused on validation, carrying one error per offending field. Rendered by
 * {@link FieldValidationExceptionHandler} as a {@link ValidationErrorResponse}, so an editor can
 * show each message beside its input rather than one joined banner.
 */
public class FieldValidationException extends RuntimeException {

  private final transient List<FieldError> fieldErrors;

  public FieldValidationException(final List<FieldError> fieldErrors) {
    super(String.join("; ", fieldErrors.stream().map(FieldError::message).toList()));
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }
}
