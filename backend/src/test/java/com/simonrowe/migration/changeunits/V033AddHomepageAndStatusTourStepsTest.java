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

/** Exercises the additive default-tour migration directly because Mongock is off in tests. */
class V033AddHomepageAndStatusTourStepsTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V033AddHomepageAndStatusTourSteps changeUnit =
      new V033AddHomepageAndStatusTourSteps();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void appendsNewStepsWithoutRenumberingExistingTourEntries() {
    Instant timestamp = Instant.parse("2026-09-04T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        existingStep("default-home-chat", 1, timestamp),
        existingStep("default-news-events", 8, timestamp),
        existingStep("operator-step", 12, timestamp)));

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc())
        .extracting(TourStep::legacyId)
        .containsExactly(
            "default-home-chat",
            "default-news-events",
            "operator-step",
            "default-home-currently",
            "default-home-writing",
            "default-platform-status");
    assertThat(tourStepRepository.findByLegacyId("default-home-currently").orElseThrow().order())
        .isEqualTo(13);
    assertThat(tourStepRepository.findByLegacyId("default-home-writing").orElseThrow().order())
        .isEqualTo(14);
    assertThat(tourStepRepository.findByLegacyId("default-platform-status").orElseThrow().order())
        .isEqualTo(15);
  }

  @Test
  void leavesAnEmptyTourForTheApplicationSeederToPopulate() {
    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc()).isEmpty();
  }

  private TourStep existingStep(final String legacyId, final int order, final Instant timestamp) {
    return new TourStep(
        null,
        legacyId,
        ".existing",
        "Existing tour content",
        null,
        "bottom",
        order,
        timestamp,
        timestamp,
        legacyId,
        "/",
        7000);
  }
}
