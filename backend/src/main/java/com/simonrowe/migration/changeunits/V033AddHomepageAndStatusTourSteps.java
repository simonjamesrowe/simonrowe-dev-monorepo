package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import com.simonrowe.tour.TourStepSeeder;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the three later tour stops introduced after the original eight-step public tour.
 *
 * <p>The steps append after all existing entries instead of renumbering the operator's tour.
 * That avoids changing the order of bespoke steps while still making the landing-page recap and
 * platform status visible to every existing tour.
 */
@ChangeUnit(id = "add-homepage-and-status-tour-steps", order = "033", author = "simonrowe")
public class V033AddHomepageAndStatusTourSteps {

  private static final Logger LOG =
      LoggerFactory.getLogger(V033AddHomepageAndStatusTourSteps.class);

  private static final Set<String> NEW_LEGACY_IDS = Set.of(
      "default-home-currently",
      "default-home-writing",
      "default-platform-status");

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    List<TourStep> existing = tourStepRepository.findAllByOrderByOrderAsc();
    if (existing.isEmpty()) {
      LOG.info(
          "No existing tour steps to extend; the application seeder will create the full tour");
      return;
    }
    Set<String> existingLegacyIds = existing.stream()
        .map(TourStep::legacyId)
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    int nextOrder = existing.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
    int added = 0;

    for (TourStep defaultStep : TourStepSeeder.defaultTourSteps(Instant.now())) {
      if (!NEW_LEGACY_IDS.contains(defaultStep.legacyId())
          || existingLegacyIds.contains(defaultStep.legacyId())) {
        continue;
      }
      tourStepRepository.save(withOrder(defaultStep, nextOrder++));
      added++;
    }

    LOG.info("Added {} homepage and status tour steps", added);
  }

  @RollbackExecution
  public void rollback() {
    // Additive migration: do not erase tour steps which an operator may have edited.
  }

  private TourStep withOrder(final TourStep step, final int order) {
    return new TourStep(
        null,
        step.title(),
        step.selector(),
        step.description(),
        step.titleImage(),
        step.position(),
        order,
        step.createdAt(),
        step.updatedAt(),
        step.legacyId(),
        step.route(),
        step.autoAdvanceMs());
  }
}
