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

/** Covers the user-facing landing-page-first tour order. */
class V036PrioritiseHomepageTourStepsTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V036PrioritiseHomepageTourSteps changeUnit =
      new V036PrioritiseHomepageTourSteps();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void placesHomepageStopsFirstAndRemovesTheRedundantNavigationChatStep() {
    Instant timestamp = Instant.parse("2026-09-04T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        step("default-home-chat", ".tour-home-chat", "/", 1, timestamp),
        step("default-site-search", ".tour-search", "/", 2, timestamp),
        step("default-ask-ai", ".top-nav__ask-ai", "/", 3, timestamp),
        step("default-profile", ".tour-profile", "/profile", 4, timestamp),
        step("default-home-currently", ".tour-currently", "/", 9, timestamp),
        step("default-home-writing", ".tour-featured-writing", "/", 10, timestamp),
        step(null, ".tour-contact", "/", 11, timestamp),
        step("default-platform-status", ".tour-status-running", "/status", 12, timestamp)));

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc())
        .extracting(TourStep::selector)
        .containsExactly(
            ".tour-home-chat",
            ".tour-search",
            ".tour-currently",
            ".tour-featured-writing",
            ".tour-contact",
            ".tour-profile",
            ".tour-status-running");
    assertThat(tourStepRepository.findByLegacyId("default-ask-ai")).isEmpty();
  }

  @Test
  void leavesAnEmptyTourForTheApplicationSeederToPopulate() {
    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc()).isEmpty();
  }

  @Test
  void assignsAnUnusedTemporaryOrderBeforeAddingTheMissingHomepageContact() {
    Instant timestamp = Instant.parse("2026-09-04T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        step("default-home-chat", ".tour-home-chat", "/", 1, timestamp),
        step("default-site-search", ".tour-search", "/", 2, timestamp),
        step("default-ask-ai", ".top-nav__ask-ai", "/", 3, timestamp),
        step("default-profile", ".tour-profile", "/profile", 4, timestamp),
        step("default-contact", ".tour-contact", "/profile#contact", 5, timestamp),
        step("default-experience", ".tour-experience", "/experience", 6, timestamp)));

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc())
        .extracting(TourStep::legacyId)
        .contains("default-home-contact")
        .doesNotContain("default-ask-ai");
  }

  private TourStep step(
      final String legacyId,
      final String selector,
      final String route,
      final int order,
      final Instant timestamp
  ) {
    return new TourStep(
        null,
        legacyId == null ? "Homepage contact" : legacyId,
        selector,
        "Description",
        null,
        "bottom",
        order,
        timestamp,
        timestamp,
        legacyId,
        route,
        7000);
  }
}
