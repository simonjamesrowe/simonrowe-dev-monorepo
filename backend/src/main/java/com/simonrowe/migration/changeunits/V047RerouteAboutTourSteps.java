package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Points every tour step on the retired Profile or Experience page at {@code /about}, the page
 * they merged into, keeping any query or hash ({@code /profile#contact} becomes
 * {@code /about#contact}).
 *
 * <p><strong>Every step, not only the defaults, and that is the point.</strong> The old
 * addresses still work for a visitor, because the frontend redirects them to {@code /about},
 * but a tour step cannot follow a redirect: {@code TourProvider} navigates to the step's route
 * and then compares {@code route.split('#')[0]} with {@code location.pathname}. A step left on
 * {@code /experience} lands on {@code /about}, the two never match, and the step silently never
 * resolves its target. An operator-created or operator-edited step is exactly as exposed as a
 * seeded one, so the rewrite is keyed on the route alone.
 *
 * <p>Only {@code route} and {@code updatedAt} change. The copy is left alone, so the narration
 * sweep has nothing to regenerate. The order is untouched, so there is no unique-index dance.
 *
 * <p>Idempotent by construction: a rewritten route no longer matches, so a replay finds nothing
 * to do and writes nothing.
 */
@ChangeUnit(id = "reroute-about-tour-steps", order = "047", author = "simonrowe")
public class V047RerouteAboutTourSteps {

  private static final Logger LOG = LoggerFactory.getLogger(V047RerouteAboutTourSteps.class);

  static final String ABOUT = "/about";
  private static final Set<String> RETIRED_PATHS = Set.of("/profile", "/experience");

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    Instant timestamp = Instant.now();
    int rerouted = 0;
    for (TourStep step : tourStepRepository.findAllByOrderByOrderAsc()) {
      String route = reroute(step.route());
      if (route == null) {
        continue;
      }
      tourStepRepository.save(new TourStep(
          step.id(), step.title(), step.selector(), step.description(), step.titleImage(),
          step.position(), step.order(), step.createdAt(), timestamp, step.legacyId(), route,
          step.autoAdvanceMs()));
      rerouted++;
    }
    LOG.info("Rerouted {} tour step(s) from /profile or /experience to /about", rerouted);
  }

  @RollbackExecution
  public void rollback() {
    // Not inverted: the old routes now only redirect, so restoring them would break the steps
    // this change unit exists to repair.
  }

  /**
   * The {@code /about} equivalent of a route on a retired page, or {@code null} when the route
   * is on any other page (or absent) and must be left alone.
   */
  static String reroute(final String route) {
    if (route == null) {
      return null;
    }
    int suffixStart = indexOfSuffix(route);
    String path = route.substring(0, suffixStart);
    if (!RETIRED_PATHS.contains(path)) {
      return null;
    }
    return ABOUT + route.substring(suffixStart);
  }

  /** Where the pathname ends: at the first {@code ?} or {@code #}, else the end of the route. */
  private static int indexOfSuffix(final String route) {
    for (int index = 0; index < route.length(); index++) {
      char character = route.charAt(index);
      if (character == '?' || character == '#') {
        return index;
      }
    }
    return route.length();
  }
}
