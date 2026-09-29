package com.simonrowe.homepage;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import java.util.List;

/** A save refused on validation, carrying one error per offending field. */
class HomePageValidationException extends RuntimeException {

  private final transient List<FieldError> fieldErrors;

  HomePageValidationException(final List<FieldError> fieldErrors) {
    super(String.join("; ", fieldErrors.stream().map(FieldError::message).toList()));
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  List<FieldError> fieldErrors() {
    return fieldErrors;
  }
}
