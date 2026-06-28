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

      TourStep step = new TourStep(
          existing.map(TourStep::id).orElse(null),
          defaultStep.title(),
          defaultStep.selector(),
          defaultStep.description(),
          defaultStep.titleImage(),
          defaultStep.position(),
          defaultStep.order(),
          existing.map(TourStep::createdAt).orElse(timestamp),
          timestamp,
          defaultStep.legacyId(),
          defaultStep.route()
      );
      tourStepRepository.save(step);
      saved++;
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
            timestamp
        ),
        defaultTourStep(
            "default-ask-ai",
            "Ask AI from anywhere",
            ".top-nav__ask-ai",
            "Open the assistant from the navigation bar on any public page.",
            "bottom",
            3,
            "/",
            timestamp
        ),
        defaultTourStep(
            "default-profile",
            "Read the profile",
            ".tour-profile",
            "Explore Simon's biography, background, and professional summary.",
            "bottom",
            4,
            "/profile",
            timestamp
        ),
        defaultTourStep(
            "default-contact",
            "Get in touch",
            ".tour-contact",
            "Use the Profile page contact section to send a message.",
            "top",
            5,
            "/profile#contact",
            timestamp
        ),
        defaultTourStep(
            "default-experience",
            "Explore experience",
            ".tour-experience",
            "Review roles, teams, systems, and delivery experience.",
            "top",
            6,
            "/experience",
            timestamp
        ),
        defaultTourStep(
            "default-blogs",
            "Read the blog",
            ".tour-blogs",
            "Browse writing about engineering, AI, architecture, and delivery.",
            "top",
            7,
            "/blogs",
            timestamp
        ),
        defaultTourStep(
            "default-news-events",
            "Find news and events",
            ".tour-news-events",
            "See recent appearances, articles, meetups, and events.",
            "top",
            8,
            "/news-events",
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
        route
    );
  }
}
