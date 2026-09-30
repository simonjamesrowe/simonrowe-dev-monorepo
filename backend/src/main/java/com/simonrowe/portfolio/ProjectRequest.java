package com.simonrowe.portfolio;

import com.simonrowe.common.Image;

/**
 * Every editable field of a project, as the editor sends it. {@code displayOrder} may be null
 * on create, which appends the project to the end; reordering goes through {@code /reorder}.
 */
public record ProjectRequest(
    String slug,
    String name,
    String tagline,
    String description,
    ProjectStatus status,
    Integer displayOrder,
    boolean published,
    Image image,
    String liveUrl,
    Integer accentHue
) {
}
