package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import com.simonrowe.tour.TourStepSeeder;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Places the useful landing-page actions before the wider site tour. */
@ChangeUnit(id = "prioritise-homepage-tour-steps", order = "036", author = "simonrowe")
public class V036PrioritiseHomepageTourSteps {

  private static final Logger LOG =
      LoggerFactory.getLogger(V036PrioritiseHomepageTourSteps.class);

  private static final String ASK_AI_LEGACY_ID = "default-ask-ai";
  private static final String HOME_CONTACT_LEGACY_ID = "default-home-contact";
  private static final List<String> DEFAULT_LEGACY_ORDER = List.of(
      "default-home-chat",
      "default-site-search",
      "default-home-currently",
      "default-home-writing",
      "default-profile",
      "default-contact",
      "default-experience",
      "default-blogs",
      "default-news-events",
      "default-platform-status");

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    List<TourStep> allSteps = new ArrayList<>(tourStepRepository.findAllByOrderByOrderAsc());
    if (allSteps.isEmpty()) {
      LOG.info(
          "No existing tour steps to prioritise; the application seeder will create the full tour");
      return;
    }
    Map<String, TourStep> defaultsByLegacyId = byLegacyId(allSteps);
    TourStep homeContact = findHomepageContact(allSteps);
    if (homeContact == null) {
      homeContact = tourStepRepository.save(
          withOrder(defaultHomeContact(), nextAvailableOrder(allSteps)));
      allSteps.add(homeContact);
    }

    TourStep askAi = defaultsByLegacyId.get(ASK_AI_LEGACY_ID);
    if (askAi != null) {
      tourStepRepository.delete(askAi);
      allSteps.remove(askAi);
    }

    List<TourStep> ordered = new ArrayList<>();
    addDefault(ordered, defaultsByLegacyId, "default-home-chat");
    addDefault(ordered, defaultsByLegacyId, "default-site-search");
    addDefault(ordered, defaultsByLegacyId, "default-home-currently");
    addDefault(ordered, defaultsByLegacyId, "default-home-writing");
    ordered.add(homeContact);
    for (String legacyId : DEFAULT_LEGACY_ORDER.subList(4, DEFAULT_LEGACY_ORDER.size())) {
      addDefault(ordered, defaultsByLegacyId, legacyId);
    }

    Set<String> orderedIds = ordered.stream()
        .map(TourStep::id)
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    List<TourStep> remaining = allSteps.stream()
        .filter(step -> !orderedIds.contains(step.id()))
        .toList();
    int nextTemporaryOrder = allSteps.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
    for (TourStep step : ordered) {
      tourStepRepository.save(withOrder(step, nextTemporaryOrder++));
    }
    for (TourStep step : remaining) {
      tourStepRepository.save(withOrder(step, nextTemporaryOrder++));
    }

    int nextOrder = 1;
    for (TourStep step : ordered) {
      tourStepRepository.save(withOrder(step, nextOrder++));
    }
    for (TourStep step : remaining) {
      tourStepRepository.save(withOrder(step, nextOrder++));
    }
    LOG.info("Prioritised {} landing-page and public-tour steps", ordered.size());
  }

  @RollbackExecution
  public void rollback() {
    // Ordering is an intentional product decision; retain all operator content.
  }

  private Map<String, TourStep> byLegacyId(final List<TourStep> steps) {
    Map<String, TourStep> result = new HashMap<>();
    for (TourStep step : steps) {
      if (step.legacyId() != null) {
        result.put(step.legacyId(), step);
      }
    }
    return result;
  }

  private TourStep findHomepageContact(final List<TourStep> steps) {
    return steps.stream()
        .filter(step -> "/".equals(step.route()) && ".tour-contact".equals(step.selector()))
        .findFirst()
        .orElse(null);
  }

  private TourStep defaultHomeContact() {
    return TourStepSeeder.defaultTourSteps(Instant.now()).stream()
        .filter(step -> HOME_CONTACT_LEGACY_ID.equals(step.legacyId()))
        .findFirst()
        .orElseThrow();
  }

  private int nextAvailableOrder(final List<TourStep> steps) {
    return steps.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
  }

  private void addDefault(
      final List<TourStep> ordered,
      final Map<String, TourStep> defaultsByLegacyId,
      final String legacyId
  ) {
    TourStep step = defaultsByLegacyId.get(legacyId);
    if (step != null) {
      ordered.add(step);
    }
  }

  private TourStep withOrder(final TourStep step, final int order) {
    return new TourStep(
        step.id(), step.title(), step.selector(), step.description(), step.titleImage(),
        step.position(), order, step.createdAt(), step.updatedAt(), step.legacyId(), step.route(),
        step.autoAdvanceMs());
  }
}
