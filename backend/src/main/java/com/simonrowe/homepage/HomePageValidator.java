package com.simonrowe.homepage;

import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.LinkTargets;
import com.simonrowe.homepage.HomePage.AskPill;
import com.simonrowe.homepage.HomePage.Cta;
import java.util.ArrayList;
import java.util.List;

/** Field-by-field rules for {@link HomePageContent}; each failure names its field. */
final class HomePageValidator {

  static final int HEADLINE_MAX = 60;
  static final int LEDE_MAX = 240;
  static final int CTA_LABEL_MAX = 32;
  static final int TOUR_LABEL_MAX = 32;
  static final int PILL_LEAD_MAX = 40;
  static final int PILL_LABEL_MAX = 40;
  static final int PILL_BUTTON_MAX = 20;

  private HomePageValidator() {
  }

  static List<FieldError> validate(final HomePageContent content) {
    List<FieldError> errors = new ArrayList<>();
    required(errors, "headlineLine1", content.headlineLine1(), HEADLINE_MAX);
    required(errors, "headlineLine2", content.headlineLine2(), HEADLINE_MAX);
    optional(errors, "lede", content.lede(), LEDE_MAX);

    Cta primary = content.primaryCta();
    required(errors, "primaryCta.label", primary == null ? null : primary.label(), CTA_LABEL_MAX);
    link(errors, "primaryCta.href", primary == null ? null : primary.href());

    Cta secondary = content.secondaryCta();
    if (secondary != null && !isBlank(secondary.label())) {
      required(errors, "secondaryCta.label", secondary.label(), CTA_LABEL_MAX);
      link(errors, "secondaryCta.href", secondary.href());
    }

    if (content.showTourLink()) {
      required(errors, "tourLinkLabel", content.tourLinkLabel(), TOUR_LABEL_MAX);
    } else {
      optional(errors, "tourLinkLabel", content.tourLinkLabel(), TOUR_LABEL_MAX);
    }

    AskPill pill = content.askPill();
    optional(errors, "askPill.lead", pill == null ? null : pill.lead(), PILL_LEAD_MAX);
    required(errors, "askPill.label", pill == null ? null : pill.label(), PILL_LABEL_MAX);
    required(errors, "askPill.buttonLabel", pill == null ? null : pill.buttonLabel(),
        PILL_BUTTON_MAX);
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

  private static void optional(
      final List<FieldError> errors, final String field, final String value, final int max
  ) {
    if (value != null && value.length() > max) {
      errors.add(new FieldError(field, field + " must be at most " + max + " characters"));
    }
  }

  private static void link(final List<FieldError> errors, final String field, final String href) {
    if (isBlank(href)) {
      errors.add(new FieldError(field, field + " is required"));
    } else if (!LinkTargets.isAllowed(href)) {
      errors.add(new FieldError(field,
          field + " must be a site path starting with / or an https:// address"));
    }
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
