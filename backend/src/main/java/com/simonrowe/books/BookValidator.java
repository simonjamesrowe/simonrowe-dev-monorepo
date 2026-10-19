package com.simonrowe.books;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.LinkTargets;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Field-by-field rules for {@link BookRequest}; each failure names its field. */
final class BookValidator {

  static final int SLUG_MAX = 80;
  static final int TITLE_MAX = 160;
  static final int SUBTITLE_MAX = 200;
  static final int AUTHOR_MAX = 80;
  static final int MAX_AUTHORS = 8;
  static final int CATEGORY_MAX = 60;
  static final int SUMMARY_MAX = 500;
  static final int DESCRIPTION_MAX = 10_000;
  static final int NOTE_MAX = 2_000;
  static final int SOURCE_LABEL_MAX = 120;
  static final int EARLIEST_YEAR = 1450;

  /**
   * Lower-case words joined by single hyphens. Possessive quantifiers, and the length is
   * checked first, so no input can make the match backtrack.
   */
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");

  private BookValidator() {
  }

  static List<FieldError> validate(final BookRequest request) {
    List<FieldError> errors = new ArrayList<>();
    String slug = request.slug();
    if (isBlank(slug)) {
      errors.add(new FieldError("slug", "slug is required"));
    } else if (slug.length() > SLUG_MAX) {
      errors.add(new FieldError("slug", "slug must be at most " + SLUG_MAX + " characters"));
    } else if (!SLUG.matcher(slug).matches()) {
      errors.add(new FieldError("slug",
          "slug must be lower-case letters and digits separated by single hyphens"));
    }
    required(errors, "title", request.title(), TITLE_MAX);
    optional(errors, "subtitle", request.subtitle(), SUBTITLE_MAX);
    validateAuthors(errors, request.authors());
    Integer year = request.publishedYear();
    int latest = Year.now().getValue() + 1;
    if (year != null && (year < EARLIEST_YEAR || year > latest)) {
      errors.add(new FieldError("publishedYear",
          "publishedYear must be between " + EARLIEST_YEAR + " and " + latest));
    }
    required(errors, "category", request.category(), CATEGORY_MAX);
    required(errors, "summary", request.summary(), SUMMARY_MAX);
    optional(errors, "description", request.description(), DESCRIPTION_MAX);
    optional(errors, "note", request.note(), NOTE_MAX);
    if (request.coverImage() != null && !isBlank(request.coverImage().url())
        && !LinkTargets.isAllowed(request.coverImage().url())) {
      errors.add(new FieldError("coverImage",
          "coverImage must be a site path or an https:// address"));
    }
    if (!isBlank(request.purchaseUrl()) && !LinkTargets.isHttpsUrl(request.purchaseUrl())) {
      errors.add(new FieldError("purchaseUrl", "purchaseUrl must be an https:// address"));
    }
    optional(errors, "sourceLabel", request.sourceLabel(), SOURCE_LABEL_MAX);
    if (!isBlank(request.sourceUrl()) && !LinkTargets.isAllowed(request.sourceUrl())) {
      errors.add(new FieldError("sourceUrl",
          "sourceUrl must be a site path or an https:// address"));
    }
    if (!isBlank(request.sourceUrl()) && isBlank(request.sourceLabel())) {
      errors.add(new FieldError("sourceLabel", "sourceLabel is required with a sourceUrl"));
    }
    if (request.displayOrder() != null && request.displayOrder() < 0) {
      errors.add(new FieldError("displayOrder", "displayOrder must not be negative"));
    }
    return errors;
  }

  private static void validateAuthors(final List<FieldError> errors, final List<String> list) {
    if (list == null || list.isEmpty()) {
      errors.add(new FieldError("authors", "authors must name at least one author"));
      return;
    }
    if (list.size() > MAX_AUTHORS) {
      errors.add(new FieldError("authors",
          "authors must have at most " + MAX_AUTHORS + " entries"));
      return;
    }
    for (int i = 0; i < list.size(); i++) {
      required(errors, "authors[" + i + "]", list.get(i), AUTHOR_MAX);
    }
  }

  private static void optional(
      final List<FieldError> errors, final String field, final String value, final int max
  ) {
    if (value != null && value.length() > max) {
      errors.add(new FieldError(field, field + " must be at most " + max + " characters"));
    }
  }

  private static void required(
      final List<FieldError> errors, final String field, final String value, final int max
  ) {
    if (isBlank(value)) {
      errors.add(new FieldError(field, field + " is required"));
    } else if (value.length() > max) {
      errors.add(new FieldError(field, field + " must be at most " + max + " characters"));
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
