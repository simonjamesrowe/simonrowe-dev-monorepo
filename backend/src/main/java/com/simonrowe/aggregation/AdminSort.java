package com.simonrowe.aggregation;

import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Sort;

/**
 * Builds an admin listing's sort from its {@code sort} and {@code direction} parameters.
 *
 * <p>Only fields on an explicit allowlist can be sorted on. The parameter names a field in
 * the stored document, so passing it through unchecked would let a request sort on
 * {@code fullContent} (a large in-memory sort over every article) or on a field that does
 * not exist, which Mongo accepts silently and which reads as "the sort is broken". An
 * unknown value is refused with a 400 rather than replaced by the default, for the same
 * reason: a sort that silently did something else is worse than an error.
 *
 * <p>Every sort ends with {@code _id} ascending. Many articles share a published date to
 * the second, and without a unique tiebreaker Mongo may order ties differently from one
 * page request to the next, so an item can appear on two pages or on none.
 */
final class AdminSort {

  private AdminSort() {
  }

  /**
   * The sort for an admin listing.
   *
   * @param field the requested field; null or blank means {@code defaultField}
   * @param direction {@code asc} or {@code desc}, in any case; null or blank means
   *     descending
   * @param allowed the fields this listing may sort on
   * @param defaultField the field used when none is requested
   * @return the sort, always ending with {@code _id} ascending
   * @throws IllegalArgumentException when the field is not allowed or the direction is
   *     neither {@code asc} nor {@code desc}
   */
  static Sort of(
      final String field,
      final String direction,
      final Set<String> allowed,
      final String defaultField) {
    String chosen = field == null || field.isBlank() ? defaultField : field.trim();
    if (!allowed.contains(chosen)) {
      throw new IllegalArgumentException(
          "sort must be one of " + String.join(", ", allowed.stream().sorted().toList()));
    }
    return Sort.by(direction(direction), chosen).and(Sort.by(Sort.Direction.ASC, "id"));
  }

  private static Sort.Direction direction(final String value) {
    if (value == null || value.isBlank()) {
      return Sort.Direction.DESC;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "asc" -> Sort.Direction.ASC;
      case "desc" -> Sort.Direction.DESC;
      default -> throw new IllegalArgumentException("direction must be asc or desc");
    };
  }
}
