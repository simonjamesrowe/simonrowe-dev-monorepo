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

/** Exercises the default-target rewrite directly because Mongock is off in tests. */
class V037NarrowTourFocusTargetsTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V037NarrowTourFocusTargets changeUnit = new V037NarrowTourFocusTargets();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void replacesOnlyThePriorDefaultSelectorsAndIsSafeToReRun() {
    Instant timestamp = Instant.parse("2026-09-04T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        step("default-profile", ".tour-profile", 1, timestamp),
        step("default-contact", ".tour-contact", 2, timestamp),
        step("default-experience", ".tour-experience", 3, timestamp),
        step("default-blogs", ".tour-blogs", 4, timestamp),
        step("default-news-events", ".tour-news-events", 5, timestamp),
        step("operator-step", ".operator-choice", 6, timestamp),
        step(null, ".legacy-free-choice", 7, timestamp)));

    changeUnit.execution(tourStepRepository);
    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findAllByOrderByOrderAsc())
        .extracting(TourStep::selector)
        .containsExactly(
            ".tour-profile-heading",
            ".tour-contact-drawer",
            ".tour-experience-highlight",
            ".tour-blog-filters",
            ".tour-news-filters",
            ".operator-choice",
            ".legacy-free-choice");
  }

  private TourStep step(
      final String legacyId,
      final String selector,
      final int order,
      final Instant timestamp
  ) {
    return new TourStep(
        null, legacyId, selector, "Description", null, "bottom", order, timestamp, timestamp,
        legacyId, "/", 7000);
  }
}
