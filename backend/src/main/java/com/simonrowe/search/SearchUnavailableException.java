package com.simonrowe.search;

public class SearchUnavailableException extends RuntimeException {

  public SearchUnavailableException(final String message) {
    super(message);
  }
}
