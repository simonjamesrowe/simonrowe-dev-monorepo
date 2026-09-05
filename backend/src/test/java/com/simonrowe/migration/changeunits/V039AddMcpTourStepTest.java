package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the MCP step migration directly because Mongock is disabled in tests. */
class V039AddMcpTourStepTest extends AbstractIntegrationTest {

  private static final Instant TIMESTAMP = Instant.parse("2026-09-05T00:00:00Z");

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V039AddMcpTourStep changeUnit = new V039AddMcpTourStep();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  /** The nine steps production served after V038, captured from its API. */
  private void saveProductionTour() {
    tourStepRepository.saveAll(List.of(
        step("default-home-chat", "Ask Simon anything", ".tour-home-chat", "/", 1),
        step("default-site-search", "Search the evidence", ".tour-search", "/", 2),
        step("default-home-currently", "The work happening now", ".tour-currently", "/", 3),
        step("default-home-writing", "Writing from the workbench", ".tour-featured-writing",
            "/", 4),
        step("default-profile", "The story behind the work", ".tour-about", "/profile", 5),
        step("default-experience", "Trace the systems and outcomes",
            ".tour-experience-highlight", "/experience", 6),
        step("default-blogs", "Go from topic to evidence", ".tour-blogs", "/blogs", 7),
        step("default-news-events", "See the wider conversation", ".tour-news-events",
            "/news-events", 8),
        step("default-platform-status", "A portfolio that runs in public",
            ".tour-status-running", "/status", 9)));
  }

  @Test
  void addsTheMcpStepBeforeThePlatformStatusStopAndIsSafeToReRun() {
    saveProductionTour();

    changeUnit.execution(tourStepRepository);
    changeUnit.execution(tourStepRepository);

    List<TourStep> saved = tourStepRepository.findAllByOrderByOrderAsc();
    assertThat(saved).hasSize(10);
    assertThat(saved).extracting(TourStep::order)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    assertThat(saved).extracting(TourStep::legacyId)
        .containsExactly(
            "default-home-chat", "default-site-search", "default-home-currently",
            "default-home-writing", "default-profile", "default-experience", "default-blogs",
            "default-news-events", "default-mcp-tools", "default-platform-status");
    assertThat(saved.get(8)).satisfies(step -> {
      assertThat(step.title()).isEqualTo("Plug your own agent in");
      assertThat(step.selector()).isEqualTo(".tour-mcp-tools");
      assertThat(step.route()).isEqualTo("/mcp");
      // Visitor-paced like every other step: the tour advances on narration, not a timer.
      assertThat(step.autoAdvanceMs()).isNull();
    });
  }

  @Test
  void addsNothingWhenTheTourAlreadyHasTheStep() {
    saveProductionTour();
    tourStepRepository.save(step("default-mcp-tools", "Plug your own agent in",
        ".tour-mcp-tools", "/mcp", 10));

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAll()).hasSize(10);
  }

  @Test
  void appendsWhenThePlatformStatusStopIsGone() {
    saveProductionTour();
    tourStepRepository.findByLegacyId("default-platform-status")
        .ifPresent(tourStepRepository::delete);

    changeUnit.execution(tourStepRepository);

    List<TourStep> saved = tourStepRepository.findAllByOrderByOrderAsc();
    assertThat(saved).hasSize(9);
    assertThat(saved.get(8).legacyId()).isEqualTo("default-mcp-tools");
  }

  @Test
  void leavesAnEmptyTourToTheApplicationSeeder() {
    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAll()).isEmpty();
  }

  private TourStep step(
      final String legacyId,
      final String title,
      final String selector,
      final String route,
      final int order
  ) {
    return new TourStep(
        null, title, selector, "Description.", null, "bottom", order,
        TIMESTAMP, TIMESTAMP, legacyId, route, null);
  }
}
