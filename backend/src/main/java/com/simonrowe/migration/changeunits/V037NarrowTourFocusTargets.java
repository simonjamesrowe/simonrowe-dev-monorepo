package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces broad page-section tour targets with concise, visible anchors so the tour spotlight
 * has a useful focus area. Only the prior default selectors change, preserving operator edits.
 * This migration is idempotent because an updated selector is never selected again.
 */
@ChangeUnit(id = "narrow-tour-focus-targets", order = "037", author = "simonrowe")
public class V037NarrowTourFocusTargets {

  private static final Logger LOG = LoggerFactory.getLogger(V037NarrowTourFocusTargets.class);
  private static final Map<String, SelectorChange> SELECTOR_CHANGES = Map.of(
      "default-profile", new SelectorChange(".tour-profile", ".tour-profile-heading"),
      "default-contact", new SelectorChange(".tour-contact", ".tour-contact-drawer"),
      "default-experience", new SelectorChange(".tour-experience", ".tour-experience-highlight"),
      "default-blogs", new SelectorChange(".tour-blogs", ".tour-blog-filters"),
      "default-news-events", new SelectorChange(".tour-news-events", ".tour-news-filters"));

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    int updated = 0;
    for (TourStep step : tourStepRepository.findAllByOrderByOrderAsc()) {
      String legacyId = step.legacyId();
      if (legacyId == null) {
        continue;
      }
      SelectorChange change = SELECTOR_CHANGES.get(legacyId);
      if (change == null || !change.previousSelector().equals(step.selector())) {
        continue;
      }
      tourStepRepository.save(withSelector(step, change.replacementSelector()));
      updated++;
    }
    LOG.info("Updated {} default tour focus targets", updated);
  }

  @RollbackExecution
  public void rollback() {
    // Preserve the updated focus targets and any operator changes made afterwards.
  }

  private TourStep withSelector(final TourStep step, final String selector) {
    return new TourStep(
        step.id(), step.title(), selector, step.description(), step.titleImage(), step.position(),
        step.order(), step.createdAt(), step.updatedAt(), step.legacyId(), step.route(),
        step.autoAdvanceMs());
  }

  private record SelectorChange(String previousSelector, String replacementSelector) {
  }
}
