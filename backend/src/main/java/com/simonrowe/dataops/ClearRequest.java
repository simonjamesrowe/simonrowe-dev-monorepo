package com.simonrowe.dataops;

public record ClearRequest(String confirmationPhrase) {

  public static final String REQUIRED_PHRASE = "DELETE ALL DATA";
}
