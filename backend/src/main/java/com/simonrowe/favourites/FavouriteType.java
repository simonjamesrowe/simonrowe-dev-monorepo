package com.simonrowe.favourites;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The kind of aggregated content a favourite refers to, keyed by the {@code {type}}
 * path segment used in the favourites API ({@code news} or {@code events}).
 */
public enum FavouriteType {
  NEWS("news"),
  EVENT("events");

  private final String pathSegment;

  FavouriteType(final String pathSegment) {
    this.pathSegment = pathSegment;
  }

  public String pathSegment() {
    return pathSegment;
  }

  /**
   * Resolves a URL path segment to a favourite type.
   *
   * @param segment the {@code {type}} path segment, e.g. {@code news}
   * @return the matching type, or empty when the segment is not recognised
   */
  public static Optional<FavouriteType> fromPathSegment(final String segment) {
    return Stream.of(values())
        .filter(type -> type.pathSegment.equals(segment))
        .findFirst();
  }
}
