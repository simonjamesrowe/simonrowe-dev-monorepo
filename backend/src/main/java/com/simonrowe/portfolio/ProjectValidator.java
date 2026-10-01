package com.simonrowe.portfolio;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.LinkTargets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Field-by-field rules for {@link ProjectRequest}; each failure names its field. */
final class ProjectValidator {

  static final int SLUG_MAX = 60;
  static final int NAME_MAX = 60;
  static final int TAGLINE_MAX = 140;
  static final int DESCRIPTION_MAX = 20_000;
  static final int HEADLINE_MAX = 120;
  static final int SUMMARY_MAX = 600;
  static final int LABEL_MAX = 40;
  static final int STATEMENT_MAX = 400;
  static final int TITLE_MAX = 80;
  static final int TEXT_MAX = 400;
  static final int ALT_MAX = 200;
  static final int QUESTION_MAX = 140;
  static final int MAX_QUESTIONS = 12;
  static final int MAX_POINTS = 6;
  static final int MAX_HIGHLIGHTS = 6;
  static final int MAX_CHAPTERS = 12;
  static final int MAX_PAGES = 6;

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
    optional(errors, "headline", request.headline(), HEADLINE_MAX);
    optional(errors, "summary", request.summary(), SUMMARY_MAX);
    validateStatement(errors, request.statement());
    validateQuestions(errors, request.exampleQuestions());
    validateHighlights(errors, "highlights", request.highlights(), MAX_HIGHLIGHTS);
    validateDemo(errors, request.demo());
    validatePages(errors, request.pages());
    return errors;
  }

  private static void validateStatement(
      final List<FieldError> errors, final ProjectStatement statement
  ) {
    if (statement == null) {
      return;
    }
    optional(errors, "statement.label", statement.label(), LABEL_MAX);
    optional(errors, "statement.text", statement.text(), STATEMENT_MAX);
    validateHighlights(errors, "statement.points", statement.points(), MAX_POINTS);
  }

  private static void validateQuestions(final List<FieldError> errors, final List<String> list) {
    if (list == null) {
      return;
    }
    if (list.size() > MAX_QUESTIONS) {
      errors.add(new FieldError("exampleQuestions",
          "exampleQuestions must have at most " + MAX_QUESTIONS + " entries"));
      return;
    }
    for (int i = 0; i < list.size(); i++) {
      required(errors, "exampleQuestions[" + i + "]", list.get(i), QUESTION_MAX);
    }
  }

  private static void validateHighlights(
      final List<FieldError> errors,
      final String field,
      final List<ProjectHighlight> list,
      final int max
  ) {
    if (list == null) {
      return;
    }
    if (list.size() > max) {
      errors.add(new FieldError(field, field + " must have at most " + max + " entries"));
      return;
    }
    for (int i = 0; i < list.size(); i++) {
      String prefix = field + "[" + i + "]";
      ProjectHighlight highlight = list.get(i);
      if (highlight == null) {
        errors.add(new FieldError(prefix, prefix + " is required"));
        continue;
      }
      required(errors, prefix + ".title", highlight.title(), TITLE_MAX);
      optional(errors, prefix + ".text", highlight.text(), TEXT_MAX);
      link(errors, prefix + ".imageUrl", highlight.imageUrl());
      optional(errors, prefix + ".imageAlt", highlight.imageAlt(), ALT_MAX);
    }
  }

  private static void validateDemo(final List<FieldError> errors, final ProjectDemo demo) {
    if (demo == null) {
      return;
    }
    if (isBlank(demo.videoUrl())) {
      errors.add(new FieldError("demo.videoUrl", "demo.videoUrl is required"));
    } else {
      link(errors, "demo.videoUrl", demo.videoUrl());
    }
    link(errors, "demo.captionsUrl", demo.captionsUrl());
    link(errors, "demo.posterUrl", demo.posterUrl());
    optional(errors, "demo.title", demo.title(), TITLE_MAX);
    optional(errors, "demo.summary", demo.summary(), TEXT_MAX);
    if (demo.chapters().size() > MAX_CHAPTERS) {
      errors.add(new FieldError("demo.chapters",
          "demo.chapters must have at most " + MAX_CHAPTERS + " entries"));
      return;
    }
    for (int i = 0; i < demo.chapters().size(); i++) {
      String prefix = "demo.chapters[" + i + "]";
      ProjectChapter chapter = demo.chapters().get(i);
      if (chapter == null) {
        errors.add(new FieldError(prefix, prefix + " is required"));
        continue;
      }
      if (chapter.startSeconds() < 0) {
        errors.add(new FieldError(prefix + ".startSeconds",
            prefix + ".startSeconds must not be negative"));
      }
      required(errors, prefix + ".label", chapter.label(), TITLE_MAX);
    }
  }

  /** Page slugs follow the project slug's rule, and must differ within one project. */
  private static void validatePages(final List<FieldError> errors, final List<ProjectPage> list) {
    if (list == null) {
      return;
    }
    if (list.size() > MAX_PAGES) {
      errors.add(new FieldError("pages", "pages must have at most " + MAX_PAGES + " entries"));
      return;
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      String prefix = "pages[" + i + "]";
      ProjectPage page = list.get(i);
      if (page == null) {
        errors.add(new FieldError(prefix, prefix + " is required"));
        continue;
      }
      String slug = page.slug();
      if (isBlank(slug) || slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
        errors.add(new FieldError(prefix + ".slug", prefix
            + ".slug must be lower-case letters and digits separated by single hyphens, at most "
            + SLUG_MAX + " characters"));
      } else if (!seen.add(slug)) {
        errors.add(new FieldError(prefix + ".slug", prefix + ".slug is used by another page"));
      }
      required(errors, prefix + ".title", page.title(), LABEL_MAX);
      optional(errors, prefix + ".navHint", page.navHint(), LABEL_MAX);
      optional(errors, prefix + ".summary", page.summary(), TEXT_MAX);
      optional(errors, prefix + ".body", page.body(), DESCRIPTION_MAX);
    }
  }

  private static void optional(
      final List<FieldError> errors, final String field, final String value, final int max
  ) {
    if (value != null && value.length() > max) {
      errors.add(new FieldError(field, field + " must be at most " + max + " characters"));
    }
  }

  /** A site path or an https URL, decided by parsing in {@link LinkTargets}. */
  private static void link(final List<FieldError> errors, final String field, final String value) {
    if (!isBlank(value) && !LinkTargets.isAllowed(value)) {
      errors.add(new FieldError(field, field + " must be a site path or an https:// address"));
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
