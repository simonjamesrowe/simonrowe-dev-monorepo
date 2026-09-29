package com.simonrowe.portfolio;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.LinkTargets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Field-by-field rules for {@link ProjectRequest}; each failure names its field. */
final class ProjectValidator {

  static final int SLUG_MAX = 60;
  static final int NAME_MAX = 60;
  static final int TAGLINE_MAX = 140;
  static final int DESCRIPTION_MAX = 20_000;

  /**
   * Lower-case words joined by single hyphens. Possessive quantifiers, and the length is
   * checked first, so no input can make the match backtrack.
   */
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");

  private ProjectValidator() {
  }

  static List<FieldError> validate(final ProjectRequest request) {
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
    required(errors, "name", request.name(), NAME_MAX);
    required(errors, "tagline", request.tagline(), TAGLINE_MAX);
    if (request.description() != null && request.description().length() > DESCRIPTION_MAX) {
      errors.add(new FieldError("description",
          "description must be at most " + DESCRIPTION_MAX + " characters"));
    }
    if (request.status() == null) {
      errors.add(new FieldError("status", "status is required"));
    }
    if (request.displayOrder() != null && request.displayOrder() < 0) {
      errors.add(new FieldError("displayOrder", "displayOrder must not be negative"));
    }
    if (!isBlank(request.liveUrl()) && !LinkTargets.isHttpsUrl(request.liveUrl())) {
      errors.add(new FieldError("liveUrl", "liveUrl must be an https:// address"));
    }
    Integer hue = request.accentHue();
    if (hue != null && (hue < 0 || hue > 359)) {
      errors.add(new FieldError("accentHue", "accentHue must be between 0 and 359"));
    }
    return errors;
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
