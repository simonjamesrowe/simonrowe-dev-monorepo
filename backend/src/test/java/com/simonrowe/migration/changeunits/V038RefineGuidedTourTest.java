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

/** Exercises the refined default tour directly because Mongock is disabled in tests. */
class V038RefineGuidedTourTest extends AbstractIntegrationTest {

  @Autowired
  private AdminTourStepRepository tourStepRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final V038RefineGuidedTour changeUnit = new V038RefineGuidedTour();

  @BeforeEach
  @AfterEach
  void dropTourSteps() {
    mongoTemplate.getCollection(V032BackfillTourStepTimings.COLLECTION).drop();
  }

  @Test
  void refinesUntouchedDefaultsIntoManualHomepageFirstTourAndIsSafeToReRun() {
    Instant timestamp = Instant.parse("2026-09-05T00:00:00Z");
    tourStepRepository.saveAll(List.of(
        step("default-home-chat", "Start with the AI chat", ".tour-home-chat",
            "Ask about Simon's work, leadership, stack, and career history.", "bottom", "/", 1,
            7000,
            timestamp),
        step("default-site-search", "Search the site", ".tour-search",
            "Search content or turn a search into an AI question.", "bottom", "/", 2, 7000,
            timestamp),
        step("default-home-currently", "See what Simon is doing now", ".tour-currently",
            "The homepage opens with Simon's current role, remit, and where he is based.", "bottom",
            "/",
            3, 8000, timestamp),
        step("default-home-writing", "Browse recent writing", ".tour-featured-writing",
            "Recent engineering writing is collected here. Use the arrows to browse, "
                + "or open the full blog.",
            "top", "/", 4, 8000, timestamp),
        step(null, "Get In Touch", ".tour-contact",
            "Interested in working together or just want to say hello? Hit the button to send me "
                + "a message directly.",
            "bottom", "/", 5, 7000,
            timestamp),
        step("default-profile", "Read the profile", ".tour-profile-heading",
            "Explore Simon's biography, background, and professional summary.", "bottom",
            "/profile",
            6,
            7000, timestamp),
        step("default-contact", "Get in touch", ".tour-contact-drawer",
            "Use the Profile page contact section to send a message.", "top", "/profile#contact", 7,
            7000,
            timestamp),
        step("default-experience", "Explore experience", ".tour-experience-highlight",
            "Review roles, teams, systems, and delivery experience.", "top", "/experience", 8, 7000,
            timestamp),
        step("default-blogs", "Read the blog", ".tour-blog-filters",
            "Browse writing about engineering, AI, architecture, and delivery.", "top", "/blogs",
            9,
            7000, timestamp),
        step("default-news-events", "Find news and events", ".tour-news-filters",
            "See recent appearances, articles, meetups, and events.", "top", "/news-events", 10,
            7000,
            timestamp),
        step("default-platform-status", "See the platform status", ".tour-status-running",
            "This live view shows the services running in production and the commit "
                + "each was built from.",
            "bottom", "/status", 11, 12000, timestamp),
        step("operator-step", "A custom stop", ".operator-choice", "Keep this unchanged.", "bottom",
            "/",
            12, 5000, timestamp),
        step(null, "A legacy-free custom stop", ".another-operator-choice", "Keep this too.",
            "bottom", "/", 13, 5000, timestamp)));

    changeUnit.execution(tourStepRepository);
    changeUnit.execution(tourStepRepository);

    List<TourStep> saved = tourStepRepository.findAllByOrderByOrderAsc();
    assertThat(saved)
        .extracting(TourStep::legacyId)
        .containsExactly(
            "default-home-chat",
            "default-site-search",
            "default-home-currently",
            "default-home-writing",
            "default-profile",
            "default-experience",
            "default-blogs",
            "default-news-events",
            "default-platform-status",
            "operator-step",
            null);
    assertThat(saved.subList(0, 9))
        .allSatisfy(step -> assertThat(step.autoAdvanceMs()).isNull());
    assertThat(saved.get(0).title()).isEqualTo("Ask Simon anything");
    // The writing and profile stops cover their whole section, not just a heading.
    assertThat(saved.get(3).selector()).isEqualTo(".tour-featured-writing");
    assertThat(saved.get(4).selector()).isEqualTo(".tour-about");
    assertThat(saved.get(8).title()).isEqualTo("A portfolio that runs in public");
    // Operator-authored stops survive untouched, including the one with no legacy id.
    assertThat(saved.get(9).title()).isEqualTo("A custom stop");
    assertThat(saved.get(9).autoAdvanceMs()).isEqualTo(5000);
    assertThat(saved.get(10).title()).isEqualTo("A legacy-free custom stop");
    // Both contact stops are gone: the profile drawer one and the homepage call to action.
    assertThat(saved).extracting(TourStep::selector)
        .doesNotContain(".tour-contact", ".tour-contact-drawer");
  }

  @Test
  void preservesAnOperatorEditedHomepageContactStop() {
    Instant timestamp = Instant.parse("2026-09-05T00:00:00Z");
    tourStepRepository.save(step(
        "default-home-contact", "Come and say hello", ".tour-contact",
        "An operator-authored call to action.", "bottom", "/", 1, null, timestamp));

    changeUnit.execution(tourStepRepository);

    // Removing a stop is right for the default wording and wrong for someone's own.
    assertThat(tourStepRepository.findByLegacyId("default-home-contact"))
        .hasValueSatisfying(step -> assertThat(step.title()).isEqualTo("Come and say hello"));
  }

  @Test
  void preservesAnOperatorEditedDefaultContactStop() {
    Instant timestamp = Instant.parse("2026-09-05T00:00:00Z");
    TourStep customisedContact = step(
        "default-contact", "Book a call", ".custom-contact", "An operator-authored contact step.",
        "bottom", "/profile#contact", 1, null, timestamp);
    tourStepRepository.save(customisedContact);

    changeUnit.execution(tourStepRepository);

    assertThat(tourStepRepository.findByLegacyId("default-contact"))
        .hasValueSatisfying(step -> assertThat(step.title()).isEqualTo("Book a call"));
  }

  private TourStep step(
      final String legacyId,
      final String title,
      final String selector,
      final String description,
      final String position,
      final String route,
      final int order,
      final Integer autoAdvanceMs,
      final Instant timestamp
  ) {
    return new TourStep(
        null, title, selector, description, null, position, order, timestamp, timestamp, legacyId,
        route, autoAdvanceMs);
  }
}
