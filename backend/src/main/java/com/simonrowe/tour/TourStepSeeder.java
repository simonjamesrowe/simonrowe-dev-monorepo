package com.simonrowe.tour;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TourStepSeeder implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(TourStepSeeder.class);

  private final AdminTourStepRepository tourStepRepository;

  public TourStepSeeder(final AdminTourStepRepository tourStepRepository) {
    this.tourStepRepository = tourStepRepository;
  }

  @Override
  public void run(final ApplicationArguments args) {
    int saved = seedDefaultTourSteps(tourStepRepository, Instant.now());
    LOG.info("Default tour steps seeded: {} upserted.", saved);
  }

  public static int seedDefaultTourSteps(
      final AdminTourStepRepository tourStepRepository,
      final Instant timestamp
  ) {
    int saved = 0;

    for (TourStep defaultStep : defaultTourSteps(timestamp)) {
      Optional<TourStep> existing = tourStepRepository
          .findByLegacyId(defaultStep.legacyId())
          .or(() -> tourStepRepository.findByOrder(defaultStep.order()));

      if (existing.isEmpty()) {
        tourStepRepository.save(defaultStep);
        saved++;
      }
    }

    return saved;
  }

  public static List<TourStep> defaultTourSteps(final Instant timestamp) {
    return List.of(
        defaultTourStep(
            "default-home-chat",
            "Start with the AI chat",
            ".tour-home-chat",
            "Ask about Simon's work, leadership, stack, and career history.",
            "bottom",
            1,
            "/",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-site-search",
            "Search the site",
            ".tour-search",
            "Search content or turn a search into an AI question.",
            "bottom",
            2,
            "/",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-home-currently",
            "See what Simon is doing now",
            ".tour-currently",
            "The homepage opens with Simon's current role, remit, and where he is based.",
            "bottom",
            3,
            "/",
            8000,
            timestamp
        ),
        defaultTourStep(
            "default-home-writing",
            "Browse recent writing",
            ".tour-featured-writing",
            "Recent engineering writing is collected here. Use the arrows to browse, "
                + "or open the full blog.",
            "top",
            4,
            "/",
            8000,
            timestamp
        ),
        defaultTourStep(
            "default-home-contact",
            "Get in touch",
            ".tour-contact",
            "The homepage closes with a direct route to start a conversation.",
            "bottom",
            5,
            "/",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-profile",
            "Read the profile",
            ".tour-profile-heading",
            "Explore Simon's biography, background, and professional summary.",
            "bottom",
            6,
            "/profile",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-contact",
            "Get in touch",
            ".tour-contact-drawer",
            "Use the Profile page contact section to send a message.",
            "top",
            7,
            "/profile#contact",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-experience",
            "Explore experience",
            ".tour-experience-highlight",
            "Review roles, teams, systems, and delivery experience.",
            "top",
            8,
            "/experience",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-blogs",
            "Read the blog",
            ".tour-blog-filters",
            "Browse writing about engineering, AI, architecture, and delivery.",
            "bottom",
            9,
            "/blogs",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-news-events",
            "Find news and events",
            ".tour-news-filters",
            "See recent appearances, articles, meetups, and events.",
            "top",
            10,
            "/news-events",
            7000,
            timestamp
        ),
        defaultTourStep(
            "default-platform-status",
            "See the platform status",
            ".tour-status-running",
            "This live view shows the services running in production and the commit "
                + "each was built from.",
            "bottom",
            11,
            "/status",
            12000,
            timestamp
        )
    );
  }

  private static TourStep defaultTourStep(
      final String legacyId,
      final String title,
      final String selector,
      final String description,
      final String position,
      final int order,
      final String route,
      final Integer autoAdvanceMs,
      final Instant timestamp
  ) {
    return new TourStep(
        null,
        title,
        selector,
        description,
        null,
        position,
        order,
        timestamp,
        timestamp,
        legacyId,
        route,
        autoAdvanceMs
    );
  }
}
