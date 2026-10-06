package com.simonrowe.aggregation;

import java.util.Locale;

/** What a bulk request does to every item it names. */
public enum BulkAction {
  HIDE,
  SHOW,
  DELETE;

  /**
   * Reads the request's {@code action}.
   *
   * @param value {@code hide}, {@code show} or {@code delete}, in any case
   * @return the action
   * @throws IllegalArgumentException for anything else, including null
   */
  public static BulkAction parse(final String value) {
    if (value == null) {
      throw new IllegalArgumentException("action must be one of hide, show, delete");
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "hide" -> HIDE;
      case "show" -> SHOW;
      case "delete" -> DELETE;
      default -> throw new IllegalArgumentException("action must be one of hide, show, delete");
    };
  }
}
