package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Makes the landing-page recap and platform status the final, ordered tour sequence. */
@ChangeUnit(id = "order-homepage-and-status-tour-steps", order = "034", author = "simonrowe")
public class V034OrderHomepageAndStatusTourSteps {

  private static final List<String> ORDERED_LEGACY_IDS = List.of(
      "default-home-currently",
      "default-home-writing",
      "default-platform-status");
  private static final Set<String> NEW_LEGACY_IDS = Set.copyOf(ORDERED_LEGACY_IDS);

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    List<TourStep> allSteps = tourStepRepository.findAllByOrderByOrderAsc();
    Map<String, TourStep> newStepsByLegacyId = allSteps.stream()
        .filter(step -> step.legacyId() != null && NEW_LEGACY_IDS.contains(step.legacyId()))
        .collect(Collectors.toMap(TourStep::legacyId, step -> step));

    if (newStepsByLegacyId.isEmpty()) {
      return;
    }

    int nextTemporaryOrder = allSteps.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
    for (String legacyId : ORDERED_LEGACY_IDS) {
      TourStep step = newStepsByLegacyId.get(legacyId);
      if (step != null) {
        tourStepRepository.save(withOrder(step, nextTemporaryOrder++));
      }
    }

    int nextOrder = allSteps.stream()
        .filter(step -> step.legacyId() == null || !NEW_LEGACY_IDS.contains(step.legacyId()))
        .mapToInt(TourStep::order)
        .max()
        .orElse(0) + 1;
    for (String legacyId : ORDERED_LEGACY_IDS) {
      TourStep step = newStepsByLegacyId.get(legacyId);
      if (step != null) {
        tourStepRepository.save(withOrder(step, nextOrder++));
      }
    }
  }

  @RollbackExecution
  public void rollback() {
    // Ordering only: retain any existing operator changes.
  }

  private TourStep withOrder(final TourStep step, final int order) {
    return new TourStep(
        step.id(),
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
