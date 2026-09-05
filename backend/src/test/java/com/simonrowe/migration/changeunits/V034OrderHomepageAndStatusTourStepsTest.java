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

/** Ensures the status view stays last even when older operator steps already exist. */
class V034OrderHomepageAndStatusTourStepsTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V034OrderHomepageAndStatusTourSteps changeUnit =
      new V034OrderHomepageAndStatusTourSteps();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void movesOnlyTheNewStepsToTheEndInTheirCanonicalOrder() {
    Instant timestamp = Instant.parse("2026-09-04T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        step(null, 9, timestamp),
        step("default-home-writing", 10, timestamp),
        step("default-platform-status", 11, timestamp),
        step("default-home-currently", 12, timestamp)));

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc())
        .extracting(TourStep::legacyId)
        .containsExactly(
            null,
            "default-home-currently",
            "default-home-writing",
            "default-platform-status");
  }

  private TourStep step(final String legacyId, final int order, final Instant timestamp) {
    return new TourStep(
        null,
        legacyId,
        ".target",
        "Description",
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
