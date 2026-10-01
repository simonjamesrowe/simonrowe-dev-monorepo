package com.simonrowe.portfolio;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A project in the Portfolio section, managed in the CMS.
 *
 * <p>{@code slug} is unique, and that is enforced by the {@code idx_portfolio_slug} index that
 * {@code V048CreatePortfolioProjects} creates — {@code auto-index-creation} is off, so an
 * {@code @Indexed} here would be decorative.
 *
 * <p>Everything from {@code headline} to {@code pages} is optional and describes the project's
 * own page: the overview's hero, statement, example questions, highlights and demo, and any
 * sub-pages. A project saved before those fields existed reads back with them null or empty, and
 * its page falls back to the name, tagline and description.
 */
@Document(collection = PortfolioProject.COLLECTION)
public record PortfolioProject(
    @Id String id,
    String slug,
    String name,
    String tagline,
    String description,
    ProjectStatus status,
    int displayOrder,
    boolean published,
    Image image,
    String liveUrl,
    int accentHue,
    String headline,
    String summary,
    ProjectStatement statement,
    List<String> exampleQuestions,
    List<ProjectHighlight> highlights,
    ProjectDemo demo,
    List<ProjectPage> pages,
    Instant createdAt,
    Instant updatedAt
) {

  public static final String COLLECTION = "portfolio_projects";

  public PortfolioProject {
    exampleQuestions = exampleQuestions == null ? List.of() : List.copyOf(exampleQuestions);
    highlights = highlights == null ? List.of() : List.copyOf(highlights);
    pages = pages == null ? List.of() : List.copyOf(pages);
  }

  /** A copy at another position in the list, so reordering cannot drop a field it never names. */
  public PortfolioProject withDisplayOrder(final int order, final Instant now) {
    return new PortfolioProject(id, slug, name, tagline, description, status, order, published,
        image, liveUrl, accentHue, headline, summary, statement, exampleQuestions, highlights,
        demo, pages, createdAt, now);
  }
}
