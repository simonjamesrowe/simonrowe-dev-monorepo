package com.simonrowe.migration.changeunits;

import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.TourStep;
import com.simonrowe.tour.TourStepSeeder;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refines the default guided tour into a visitor-controlled, homepage-first story.
 *
 * <p>Three things happen: the two contact stops are removed, the remaining seeded steps are
 * rewritten to the current defaults, and the survivors are renumbered into a gap-free sequence.
 *
 * <p><strong>Only steps still matching their previous seeded defaults are touched.</strong>
 * Every field is compared before a step is rewritten or deleted, so an operator who edited a
 * step in the admin CMS keeps their wording — and keeps the step, even one this migration would
 * otherwise remove. That is deliberate: silently deleting someone's edited content is worse than
 * leaving a stop in the tour that the default no longer has.
 *
 * <p>Idempotent by construction: a revised step no longer matches the previous default it was
 * matched on, a removed step is no longer present, and the resulting order is already sequential.
 */
@ChangeUnit(id = "refine-guided-tour", order = "038", author = "simonrowe")
public class V038RefineGuidedTour {

  private static final Logger LOG = LoggerFactory.getLogger(V038RefineGuidedTour.class);
  private static final String HOMEPAGE_CONTACT_SELECTOR = ".tour-contact";
  private static final List<String> DEFAULT_ORDER = List.of(
      "default-home-chat",
      "default-site-search",
      "default-home-currently",
      "default-home-writing",
      "default-profile",
      "default-experience",
      "default-blogs",
      "default-news-events",
      "default-mcp-tools",
      "default-platform-status");
  private static final Map<String, PreviousDefault> PREVIOUS_DEFAULTS = Map.ofEntries(
      Map.entry("default-home-chat", new PreviousDefault(
          "Start with the AI chat", ".tour-home-chat",
          "Ask about Simon's work, leadership, stack, and career history.", "bottom", "/", 7000)),
      Map.entry("default-site-search", new PreviousDefault(
          "Search the site", ".tour-search", "Search content or turn a search into an AI question.",
          "bottom", "/", 7000)),
      Map.entry("default-home-currently", new PreviousDefault(
          "See what Simon is doing now", ".tour-currently",
          "The homepage opens with Simon's current role, remit, and where he is based.",
          "bottom", "/", 8000)),
      Map.entry("default-home-writing", new PreviousDefault(
          "Browse recent writing", ".tour-featured-writing",
          "Recent engineering writing is collected here. Use the arrows to browse, "
              + "or open the full blog.",
          "top", "/", 8000)),
      Map.entry("default-profile", new PreviousDefault(
          "Read the profile", ".tour-profile-heading",
          "Explore Simon's biography, background, and professional summary.",
          "bottom", "/profile", 7000)),
      Map.entry("default-experience", new PreviousDefault(
          "Explore experience", ".tour-experience-highlight",
          "Review roles, teams, systems, and delivery experience.",
          "top", "/experience", 7000)),
      Map.entry("default-blogs", new PreviousDefault(
          "Read the blog", ".tour-blog-filters",
          "Browse writing about engineering, AI, architecture, and delivery.",
          "bottom", "/blogs", 7000)),
      Map.entry("default-news-events", new PreviousDefault(
          "Find news and events", ".tour-news-filters",
          "See recent appearances, articles, meetups, and events.", "top", "/news-events", 7000)),
      Map.entry("default-platform-status", new PreviousDefault(
          "See the platform status", ".tour-status-running",
          "This live view shows the services running in production and the commit "
              + "each was built from.",
          "bottom", "/status", 12000)));
  /**
   * The two contact stops this migration removes, and the previous default each must still
   * match to be removable.
   *
   * <p>The homepage stop appears in two shapes because it predates being given a
   * {@code legacyId}: the seeded {@code default-home-contact}, and an untagged step on the same
   * route and selector carrying the original wording. Both are the same stop to a visitor.
   */
  private static final Map<String, PreviousDefault> REMOVED_BY_LEGACY_ID = Map.of(
      "default-contact", new PreviousDefault(
          "Get in touch", ".tour-contact-drawer",
          "Use the Profile page contact section to send a message.",
          "top", "/profile#contact", 7000),
      "default-home-contact", new PreviousDefault(
          "Get in touch", HOMEPAGE_CONTACT_SELECTOR,
          "The homepage closes with a direct route to start a conversation.",
          "bottom", "/", 7000));
  private static final PreviousDefault UNTAGGED_HOMEPAGE_CONTACT = new PreviousDefault(
      "Get In Touch", HOMEPAGE_CONTACT_SELECTOR,
      "Interested in working together or just want to say hello? Hit the button to send me "
          + "a message directly.",
      "bottom", "/", 7000);

  @Execution
  public void execution(final AdminTourStepRepository tourStepRepository) {
    List<TourStep> existing = new ArrayList<>(tourStepRepository.findAllByOrderByOrderAsc());
    if (existing.isEmpty()) {
      LOG.info("No existing tour steps to refine; the application seeder will create the new tour");
      return;
    }

    int removed = removeRetiredContactSteps(tourStepRepository, existing);
    Map<String, TourStep> revisedDefaults = TourStepSeeder.defaultTourSteps(Instant.now()).stream()
        .collect(Collectors.toMap(TourStep::legacyId, step -> step));
    Instant timestamp = Instant.now();
    List<TourStep> revised = new ArrayList<>();
    int updated = 0;
    for (TourStep step : existing) {
      TourStep replacement = replacementFor(step, revisedDefaults, timestamp);
      revised.add(replacement);
      if (replacement != step) {
        updated++;
      }
    }

    List<TourStep> ordered = orderedSteps(revised);
    if (updated > 0 || removed > 0 || !hasSequentialOrder(ordered)) {
      saveInStableOrder(tourStepRepository, ordered);
    }
    LOG.info("Refined {} seeded tour steps and removed {} contact step(s)", updated, removed);
  }

  @RollbackExecution
  public void rollback() {
    // Tour copy and sequence are a product decision; preserve changes made by operators afterwards.
  }

  /**
   * Deletes the contact stops, but only where they still carry their seeded wording.
   *
   * @param tourStepRepository the repository
   * @param steps the current steps, mutated to drop whatever is deleted
   * @return how many were removed
   */
  private int removeRetiredContactSteps(
      final AdminTourStepRepository tourStepRepository,
      final List<TourStep> steps
  ) {
    int removed = 0;
    for (TourStep step : List.copyOf(steps)) {
      PreviousDefault retired = step.legacyId() == null
          ? untaggedHomepageContactDefault(step)
          : REMOVED_BY_LEGACY_ID.get(step.legacyId());
      if (retired != null && retired.matches(step)) {
        tourStepRepository.delete(step);
        steps.remove(step);
        removed++;
      }
    }
    return removed;
  }

  private PreviousDefault untaggedHomepageContactDefault(final TourStep step) {
    return isHomepageContact(step) ? UNTAGGED_HOMEPAGE_CONTACT : null;
  }

  private TourStep replacementFor(
      final TourStep step,
      final Map<String, TourStep> revisedDefaults,
      final Instant timestamp
  ) {
    String legacyId = step.legacyId();
    if (legacyId == null) {
      return step;
    }
    PreviousDefault previous = PREVIOUS_DEFAULTS.get(legacyId);
    TourStep revised = revisedDefaults.get(legacyId);
    if (previous == null || revised == null || !previous.matches(step)) {
      return step;
    }
    return new TourStep(
        step.id(), revised.title(), revised.selector(), revised.description(), step.titleImage(),
        revised.position(), step.order(), step.createdAt(), timestamp, step.legacyId(),
        revised.route(), revised.autoAdvanceMs());
  }

  /**
   * Puts the survivors back into the default narrative order.
   *
   * <p>Anything not in {@link #DEFAULT_ORDER} — an operator's own step, or a default they edited
   * enough that it was left alone — keeps its relative position and is appended after the known
   * ones rather than dropped.
   */
  private List<TourStep> orderedSteps(final List<TourStep> steps) {
    Map<String, TourStep> byLegacyId = steps.stream()
        .filter(step -> step.legacyId() != null)
        .collect(Collectors.toMap(TourStep::legacyId, step -> step, (left, right) -> left));
    List<TourStep> ordered = new ArrayList<>();
    for (String legacyId : DEFAULT_ORDER) {
      TourStep step = byLegacyId.get(legacyId);
      if (step != null) {
        ordered.add(step);
      }
    }
    Set<String> orderedIds = ordered.stream().map(TourStep::id).collect(Collectors.toSet());
    steps.stream().filter(step -> !orderedIds.contains(step.id())).forEach(ordered::add);
    return ordered;
  }

  private boolean isHomepageContact(final TourStep step) {
    return "/".equals(step.route()) && HOMEPAGE_CONTACT_SELECTOR.equals(step.selector());
  }

  private boolean hasSequentialOrder(final List<TourStep> steps) {
    for (int index = 0; index < steps.size(); index++) {
      if (steps.get(index).order() != index + 1) {
        return false;
      }
    }
    return true;
  }

  private void saveInStableOrder(
      final AdminTourStepRepository tourStepRepository,
      final List<TourStep> steps
  ) {
    int temporaryOrder = steps.stream().mapToInt(TourStep::order).max().orElse(0) + 1;
    for (TourStep step : steps) {
      tourStepRepository.save(withOrder(step, temporaryOrder++));
    }
    for (int index = 0; index < steps.size(); index++) {
      tourStepRepository.save(withOrder(steps.get(index), index + 1));
    }
  }

  private TourStep withOrder(final TourStep step, final int order) {
    return new TourStep(
        step.id(), step.title(), step.selector(), step.description(), step.titleImage(),
        step.position(), order, step.createdAt(), step.updatedAt(), step.legacyId(), step.route(),
        step.autoAdvanceMs());
  }

  private record PreviousDefault(
      String title,
      String selector,
      String description,
      String position,
      String route,
      Integer autoAdvanceMs
  ) {
    private boolean matches(final TourStep step) {
      return Objects.equals(title, step.title())
          && Objects.equals(selector, step.selector())
          && Objects.equals(description, step.description())
          && (Objects.equals(position, step.position())
              || ("default-blogs".equals(step.legacyId()) && "top".equals(step.position())))
          && Objects.equals(route, step.route())
          && Objects.equals(autoAdvanceMs, step.autoAdvanceMs())
          && step.titleImage() == null;
    }
  }
}
