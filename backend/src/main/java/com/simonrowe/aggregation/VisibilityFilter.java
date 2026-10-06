package com.simonrowe.aggregation;

import java.util.Locale;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Which items an admin listing shows by their {@code visible} flag.
 *
 * <p>Admin listings default to {@link #ALL}: the admin console is where hidden items are
 * found and brought back, so leaving them out by default would hide exactly what it exists
 * to manage.
 */
public enum VisibilityFilter {
  ALL,
  VISIBLE,
  HIDDEN;

  /**
   * Reads the {@code visibility} request parameter.
   *
   * @param value {@code all}, {@code visible} or {@code hidden}, in any case; null or blank
   *     means {@link #ALL}
   * @return the filter
   * @throws IllegalArgumentException for any other value, so a typo is refused rather than
   *     quietly showing everything
   */
  public static VisibilityFilter parse(final String value) {
    if (value == null || value.isBlank()) {
      return ALL;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "all" -> ALL;
      case "visible" -> VISIBLE;
      case "hidden" -> HIDDEN;
      default -> throw new IllegalArgumentException(
          "visibility must be one of all, visible, hidden");
    };
  }

  /**
   * The clause this filter adds to a query.
   *
   * @return the clause, empty for {@link #ALL}
   */
  Optional<Criteria> criteria() {
    return switch (this) {
      case ALL -> Optional.empty();
      case VISIBLE -> Optional.of(Criteria.where("visible").is(true));
      case HIDDEN -> Optional.of(Criteria.where("visible").is(false));
    };
  }
}
