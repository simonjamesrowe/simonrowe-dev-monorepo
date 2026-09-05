package com.simonrowe.tour;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.admin.TourStep;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TourSeedDefaultsTest {

  @Test
  void defaultTourStepsTargetLandingProfileAndPublicContent() {
    final List<TourStep> steps =
        TourStepSeeder.defaultTourSteps(Instant.parse("2026-06-28T00:00:00Z"));

    assertThat(steps).hasSize(10);
    assertThat(steps).extracting(TourStep::selector)
        .containsExactly(
            ".tour-home-chat",
            ".tour-search",
            ".tour-currently",
            ".tour-featured-writing-header",
            ".tour-contact",
            ".tour-profile-heading",
            ".tour-experience-highlight",
            ".tour-blog-filters",
            ".tour-news-filters",
            ".tour-status-running"
    );
    assertThat(steps).extracting(TourStep::route)
        .containsExactly(
            "/",
            "/",
            "/",
            "/",
            "/",
            "/profile",
            "/experience",
            "/blogs",
            "/news-events",
            "/status"
    );
    assertThat(steps).extracting(TourStep::autoAdvanceMs)
        .containsOnlyNulls();
  }

  @Test
  void defaultTourStepsDoNotReferenceRemovedHomepageTargets() {
    final List<TourStep> steps =
        TourStepSeeder.defaultTourSteps(Instant.parse("2026-06-28T00:00:00Z"));

    assertThat(steps).extracting(TourStep::selector)
        .doesNotContain(".about-section", ".cta-section", ".contact-drawer__close");
    assertThat(steps).extracting(TourStep::description)
        .allSatisfy(description ->
            assertThat(description).doesNotContain("drawer", "About section"));
  }
}
