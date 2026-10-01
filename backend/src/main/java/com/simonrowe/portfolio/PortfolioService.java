package com.simonrowe.portfolio;

import com.simonrowe.admin.FieldValidationException;
import com.simonrowe.admin.ValidationErrorResponse.FieldError;
import com.simonrowe.common.Image;
import com.simonrowe.media.MediaImageHydrator;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortfolioService {

  static final int DEFAULT_HUE = 212;

  private final PortfolioProjectRepository repository;
  private final MediaImageHydrator imageHydrator;

  public PortfolioService(
      final PortfolioProjectRepository repository,
      final MediaImageHydrator imageHydrator
  ) {
    this.repository = repository;
    this.imageHydrator = imageHydrator;
  }

  /** Published projects in display order, Coming soon ones reduced to their public shape. */
  public List<PublicProject> publicList() {
    return repository.findByPublishedTrueOrderByDisplayOrderAscNameAsc().stream()
        .map(this::toPublic)
        .toList();
  }

  /**
   * A project's detail page. Empty for an unknown or unpublished slug, and for a Coming soon
   * project too: there is nothing behind its silhouette to show yet.
   */
  public Optional<PublicProject> publicDetail(final String slug) {
    return repository.findBySlugAndPublishedTrue(slug)
        .filter(project -> project.status() != ProjectStatus.COMING_SOON)
        .map(this::toPublic);
  }

  public List<PortfolioProject> adminList() {
    return repository.findAllByOrderByDisplayOrderAscNameAsc();
  }

  public PortfolioProject adminGet(final String id) {
    return repository.findById(id).orElseThrow(() -> notFound(id));
  }

  public PortfolioProject create(final ProjectRequest request) {
    validate(request);
    int order = request.displayOrder() != null
        ? request.displayOrder()
        : repository.findAllByOrderByDisplayOrderAscNameAsc().stream()
            .mapToInt(PortfolioProject::displayOrder).max().orElse(-1) + 1;
    Instant now = Instant.now();
    return saveUnique(toDocument(null, request, order, now, now));
  }

  /** Replaces every editable field; the display order is kept unless the request sets one. */
  public PortfolioProject update(final String id, final ProjectRequest request) {
    PortfolioProject existing = adminGet(id);
    validate(request);
    int order = request.displayOrder() != null ? request.displayOrder() : existing.displayOrder();
    return saveUnique(toDocument(id, request, order, existing.createdAt(), Instant.now()));
  }

  public void delete(final String id) {
    if (!repository.existsById(id)) {
      throw notFound(id);
    }
    repository.deleteById(id);
  }

  /** Reorders every project. The list must name each existing project exactly once. */
  public void reorder(final List<String> orderedIds) {
    Map<String, PortfolioProject> byId = repository.findAll().stream()
        .collect(Collectors.toMap(PortfolioProject::id, Function.identity()));
    if (orderedIds == null
        || orderedIds.size() != byId.size()
        || new HashSet<>(orderedIds).size() != orderedIds.size()
        || !byId.keySet().containsAll(orderedIds)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "orderedIds must list every project exactly once");
    }
    Instant now = Instant.now();
    for (int i = 0; i < orderedIds.size(); i++) {
      PortfolioProject p = byId.get(orderedIds.get(i));
      if (p.displayOrder() != i) {
        repository.save(p.withDisplayOrder(i, now));
      }
    }
  }

  /**
   * Inserts or saves and lets the unique slug index decide, rather than checking first: a
   * read-then-write check is a race two concurrent saves can both pass.
   */
  private PortfolioProject saveUnique(final PortfolioProject project) {
    try {
      return repository.save(project);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Another project already uses the slug " + project.slug());
    }
  }

  private PublicProject toPublic(final PortfolioProject project) {
    boolean comingSoon = project.status() == ProjectStatus.COMING_SOON;
    return new PublicProject(
        project.slug(),
        project.name(),
        project.tagline(),
        project.status(),
        project.accentHue(),
        project.displayOrder(),
        comingSoon ? null : project.description(),
        comingSoon ? null : hydrate(project.image()),
        comingSoon ? null : project.liveUrl(),
        comingSoon ? null : project.headline(),
        comingSoon ? null : project.summary(),
        comingSoon ? null : project.statement(),
        comingSoon ? null : nullIfEmpty(project.exampleQuestions()),
        comingSoon ? null : nullIfEmpty(project.highlights()),
        comingSoon ? null : project.demo(),
        comingSoon ? null : nullIfEmpty(project.pages()));
  }

  private static <T> List<T> nullIfEmpty(final List<T> values) {
    return values == null || values.isEmpty() ? null : values;
  }

  private Image hydrate(final Image image) {
    if (image == null || image.url() == null) {
      return null;
    }
    // Kept even if the hydrator never returns null today: it is the media library's code,
    // and the NPE from a future change there would be on a public page.
    Image hydrated = imageHydrator.hydrate(image, "large", "medium", "small");
    return hydrated != null ? hydrated : image;
  }

  private static void validate(final ProjectRequest request) {
    List<FieldError> errors = ProjectValidator.validate(request);
    if (!errors.isEmpty()) {
      throw new FieldValidationException(errors);
    }
  }

  private static PortfolioProject toDocument(
      final String id,
      final ProjectRequest request,
      final int order,
      final Instant createdAt,
      final Instant updatedAt
  ) {
    Image image = request.image() == null || isBlank(request.image().url())
        ? null
        : new Image(request.image().url(), null, null, null, null, null);
    return new PortfolioProject(
        id,
        request.slug(),
        request.name().strip(),
        request.tagline().strip(),
        isBlank(request.description()) ? null : request.description(),
        request.status(),
        order,
        request.published(),
        image,
        isBlank(request.liveUrl()) ? null : request.liveUrl().strip(),
        request.accentHue() != null ? request.accentHue() : DEFAULT_HUE,
        clean(request.headline()),
        clean(request.summary()),
        statement(request.statement()),
        request.exampleQuestions() == null ? List.of()
            : request.exampleQuestions().stream().map(String::strip).toList(),
        request.highlights() == null ? List.of()
            : request.highlights().stream().map(PortfolioService::highlight).toList(),
        demo(request.demo()),
        request.pages() == null ? List.of()
            : request.pages().stream().map(PortfolioService::page).toList(),
        createdAt,
        updatedAt);
  }

  /** A statement with neither text nor points is no statement, and is stored as absent. */
  private static ProjectStatement statement(final ProjectStatement statement) {
    if (statement == null || (isBlank(statement.text()) && statement.points().isEmpty())) {
      return null;
    }
    return new ProjectStatement(clean(statement.label()), clean(statement.text()),
        statement.points().stream().map(PortfolioService::highlight).toList());
  }

  private static ProjectHighlight highlight(final ProjectHighlight highlight) {
    return new ProjectHighlight(clean(highlight.title()), clean(highlight.text()),
        clean(highlight.imageUrl()), clean(highlight.imageAlt()));
  }

  private static ProjectDemo demo(final ProjectDemo demo) {
    if (demo == null) {
      return null;
    }
    return new ProjectDemo(clean(demo.title()), clean(demo.summary()), clean(demo.videoUrl()),
        clean(demo.captionsUrl()), clean(demo.posterUrl()),
        demo.chapters().stream()
            .map(chapter -> new ProjectChapter(chapter.startSeconds(), clean(chapter.label())))
            .toList());
  }

  /** The body is markdown, where leading indentation means something, so it is kept verbatim. */
  private static ProjectPage page(final ProjectPage page) {
    return new ProjectPage(clean(page.slug()), clean(page.title()), clean(page.navHint()),
        clean(page.summary()), isBlank(page.body()) ? null : page.body());
  }

  private static String clean(final String value) {
    return isBlank(value) ? null : value.strip();
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static ResponseStatusException notFound(final String id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio project not found: " + id);
  }
}
