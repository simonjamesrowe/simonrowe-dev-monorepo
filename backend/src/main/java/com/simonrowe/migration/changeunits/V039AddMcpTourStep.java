package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the MCP stop to an existing guided tour, between the news feed and the platform status.
 *
 * <p><strong>The application seeder cannot do this, which is why the change unit exists.</strong>
 * {@code TourStepSeeder} treats a step as already present when either its legacy id <em>or</em>
 * its order is taken. After {@code V038} renumbered the tour to nine steps, the order this stop
 * wants is held by the platform status stop, so the seeder skipped it and the step never
 * appeared. Verified against production after that deploy: nine steps, no MCP stop. A fresh
 * installation is unaffected — the seeder creates the whole tour there, MCP stop included.
 *
 * <p>Guarded on the legacy id so a replay against a database that already has the stop adds
 * nothing. That guard is not what stops an operator's deliberate deletion coming back — only
 * Mongock's ledger is, since a recorded unit never runs again on that database.
 */
@ChangeUnit(id = "add-mcp-tour-step", order = "039", author = "simonrowe")
public class V039AddMcpTourStep {

  private static final Logger LOG = LoggerFactory.getLogger(V039AddMcpTourStep.class);

  private static final String LEGACY_ID = "default-mcp-tools";
  /** The stop this lands in front of, so the tour still ends on the live platform view. */
  private static final String PRECEDES_LEGACY_ID = "default-platform-status";
  private static final String TITLE = "Plug your own agent in";
  private static final String SELECTOR = ".tour-mcp-tools";
  private static final String DESCRIPTION =
      "This site is also a Model Context Protocol server. These are the tools it exposes — "
          + "run them here, or connect your own agent and call them directly.";
  private static final String POSITION = "top";
  private static final String ROUTE = "/mcp";

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    List<TourStep> steps = new ArrayList<>(tourStepRepository.findAllByOrderByOrderAsc());
    if (steps.isEmpty()) {
      LOG.info("No tour to add the MCP step to; the application seeder will create it");
      return;
    }
    if (steps.stream().anyMatch(step -> LEGACY_ID.equals(step.legacyId()))) {
      LOG.info("Tour already has the MCP step");
      return;
    }

    Instant timestamp = Instant.now();
    TourStep mcpStep = tourStepRepository.save(new TourStep(
        null, TITLE, SELECTOR, DESCRIPTION, null, POSITION,
        nextFreeOrder(steps), timestamp, timestamp, LEGACY_ID, ROUTE, null));

    steps.add(insertionIndex(steps), mcpStep);
    renumberSequentially(tourStepRepository, steps);
    LOG.info("Added the MCP tour step at position {}", insertionIndex(steps) + 1);
  }

  @RollbackExecution
  public void rollback() {
    // The tour's shape is a product decision; preserve whatever an operator has done since.
  }

  /**
   * Where the stop belongs: immediately before the platform status stop, or last if an operator
   * has removed that one.
   */
  private int insertionIndex(final List<TourStep> steps) {
    for (int index = 0; index < steps.size(); index++) {
      if (PRECEDES_LEGACY_ID.equals(steps.get(index).legacyId())) {
        return index;
      }
    }
    return steps.size();
  }

  /** An order no existing step holds, so the initial insert cannot collide on the unique index. */
  private int nextFreeOrder(final List<TourStep> steps) {
    return steps.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
  }

  /**
   * Renumbers in two passes, parking everything beyond the current maximum first.
   *
   * <p>{@code order} carries a unique index, so assigning final positions directly would collide
   * with whichever step still holds the number being written.
   */
  private void renumberSequentially(
      final AdminTourStepRepository tourStepRepository,
      final List<TourStep> steps
  ) {
    int parked = nextFreeOrder(steps);
    for (TourStep step : steps) {
      tourStepRepository.save(withOrder(step, parked++));
    }
    for (int index = 0; index < steps.size(); index++) {
      tourStepRepository.save(withOrder(steps.get(index), index + 1));
    }
  }

  private TourStep withOrder(final TourStep step, final int order) {
    return new TourStep(
        step.id(), step.title(), step.selector(), step.description(), step.titleImage(),
        step.position(), order, step.createdAt(), step.updatedAt(), step.legacyId(), step.route(),
        step.autoAdvanceMs());
  }
}
