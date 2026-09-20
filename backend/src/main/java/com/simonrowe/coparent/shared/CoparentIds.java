package com.simonrowe.coparent.shared;

import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Strict ObjectId parsing for the public contract. */
public final class CoparentIds {

  private CoparentIds() {
  }

  /** Parses an external identifier or returns a controlled client error. */
  public static ObjectId parse(final String value) {
    if (!ObjectId.isValid(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed identifier");
    }
    return new ObjectId(value);
  }
}
