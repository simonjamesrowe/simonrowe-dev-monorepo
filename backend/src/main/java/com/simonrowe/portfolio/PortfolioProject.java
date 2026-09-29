package com.simonrowe.portfolio;

import com.simonrowe.common.Image;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A project in the Portfolio section, managed in the CMS.
 *
 * <p>{@code slug} is unique, and that is enforced by the {@code idx_portfolio_slug} index that
 * {@code V048CreatePortfolioProjects} creates — {@code auto-index-creation} is off, so an
 * {@code @Indexed} here would be decorative.
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
    Instant createdAt,
    Instant updatedAt
) {

  public static final String COLLECTION = "portfolio_projects";
}
