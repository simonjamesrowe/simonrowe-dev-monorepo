package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Exercises the About reroute directly because Mongock is disabled in tests. */
class V047RerouteAboutTourStepsTest extends AbstractIntegrationTest {

  private static final Instant TIMESTAMP = Instant.parse("2026-09-05T00:00:00Z");

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V047RerouteAboutTourSteps changeUnit = new V047RerouteAboutTourSteps();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  /** The ten steps production serves after V039, plus two an operator added by hand. */
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
        step("default-mcp-tools", "Plug your own agent in", ".tour-mcp-tools", "/mcp", 9),
        step("default-platform-status", "A portfolio that runs in public",
            ".tour-status-running", "/status", 10),
        // Operator-created steps carry no legacy id; they are exposed exactly like the defaults.
        step(null, "Say hello", ".tour-profile-heading", "/profile#contact", 11),
        step(null, "Every skill, grouped", ".tour-skills", "/experience#skills", 12)));
  }

  @Test
  void reroutesTheDefaultProfileAndExperienceStepsToAbout() {
    saveProductionTour();

    changeUnit.execution(tourStepRepository);

    Map<Integer, TourStep> byOrder = byOrder();
    assertThat(byOrder.get(5).route()).isEqualTo("/about");
    assertThat(byOrder.get(6).route()).isEqualTo("/about");
  }

  @Test
  void reroutesOperatorStepsAndKeepsTheirHash() {
    saveProductionTour();

    changeUnit.execution(tourStepRepository);

    Map<Integer, TourStep> byOrder = byOrder();
    assertThat(byOrder.get(11).route()).isEqualTo("/about#contact");
    assertThat(byOrder.get(12).route()).isEqualTo("/about#skills");
  }

  @Test
  void changesOnlyTheRouteAndUpdatedAtOfEachRewrittenStep() {
    saveProductionTour();
    TourStep before = tourStepRepository.findByLegacyId("default-experience").orElseThrow();

    changeUnit.execution(tourStepRepository);

    TourStep after = tourStepRepository.findByLegacyId("default-experience").orElseThrow();
    assertThat(after.updatedAt()).isAfter(before.updatedAt());
    assertThat(after)
        .usingRecursiveComparison()
        .ignoringFields("route", "updatedAt")
        .isEqualTo(before);
  }

  @Test
  void leavesStepsOnOtherPagesUntouched() {
    saveProductionTour();
    tourStepRepository.save(
        step(null, "Near miss", ".tour-blogs", "/experienced-engineers", 13));
    Map<Integer, TourStep> before = byOrder();

    changeUnit.execution(tourStepRepository);

    Map<Integer, TourStep> after = byOrder();
    for (int order : List.of(1, 2, 3, 4, 7, 8, 9, 10, 13)) {
      assertThat(after.get(order)).as("step %d", order).isEqualTo(before.get(order));
    }
  }

  @Test
  void changesNothingWhenRunTwice() {
    saveProductionTour();

    changeUnit.execution(tourStepRepository);
    Map<Integer, TourStep> afterFirstRun = byOrder();
    changeUnit.execution(tourStepRepository);

    // Nothing matches a second time, so not even updatedAt moves.
    assertThat(byOrder()).isEqualTo(afterFirstRun);
    assertThat(afterFirstRun.values()).extracting(TourStep::route)
        .noneMatch(route -> route.startsWith("/profile") || route.startsWith("/experience"));
  }

  @Test
  void doesNothingToAnEmptyTour() {
    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAll()).isEmpty();
  }

  @Test
  void reroutesQueryStringsAndLeavesNullRoutesAlone() {
    assertThat(V047RerouteAboutTourSteps.reroute("/experience?job=abc#roles"))
        .isEqualTo("/about?job=abc#roles");
    assertThat(V047RerouteAboutTourSteps.reroute("/profile")).isEqualTo("/about");
    assertThat(V047RerouteAboutTourSteps.reroute("/about")).isNull();
    assertThat(V047RerouteAboutTourSteps.reroute("/profile/extra")).isNull();
    assertThat(V047RerouteAboutTourSteps.reroute(null)).isNull();
  }

  private Map<Integer, TourStep> byOrder() {
    return tourStepRepository.findAll().stream()
        .collect(Collectors.toMap(TourStep::order, Function.identity()));
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
