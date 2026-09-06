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
            "Ask Simon anything",
            ".tour-home-chat",
            "Ask about a platform decision, leadership challenge, or career chapter. "
                + "Answers are grounded in the work across this site.",
            "bottom",
            1,
            "/",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-site-search",
            "Search the evidence",
            ".tour-search",
            "Search posts, projects, and appearances — then turn a result into a "
                + "deeper question.",
            "bottom",
            2,
            "/",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-home-currently",
            "The work happening now",
            ".tour-currently",
            "Start with Simon's current remit, the teams he leads, and the transformation "
                + "underway.",
            "bottom",
            3,
            "/",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-home-writing",
            "Writing from the workbench",
            ".tour-featured-writing",
            "Explore practical thinking on AI-native delivery, platform engineering, "
                + "and technical leadership.",
            "top",
            4,
            "/",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-profile",
            "The story behind the work",
            ".tour-about",
            "See the path from hands-on engineering to leading teams through complex change.",
            "bottom",
            5,
            "/profile",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-experience",
            "Trace the systems and outcomes",
            ".tour-experience-highlight",
            "Follow the roles, teams, systems, and delivery outcomes that shaped the work.",
            "top",
            6,
            "/experience",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-blogs",
            "Go from topic to evidence",
            ".tour-blogs",
            "Filter the writing by the engineering questions you want to investigate.",
            "bottom",
            7,
            "/blogs",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-news-events",
            "See the wider conversation",
            ".tour-news-events",
            "Find recent appearances, articles, meetups, and events beyond the blog.",
            "top",
            8,
            "/news-events",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-mcp-tools",
            "Plug your own agent in",
            ".tour-mcp-tools",
            "This site is also a Model Context Protocol server. These are the tools it "
                + "exposes — run them here, or connect your own agent and call them directly.",
            "top",
            9,
            "/mcp",
            null,
            timestamp
        ),
        defaultTourStep(
            "default-platform-status",
            "A portfolio that runs in public",
            ".tour-status-running",
            "Finish with the live platform view: what is running, what shipped, and the "
                + "build serving this site right now.",
            "bottom",
            10,
            "/status",
            null,
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
