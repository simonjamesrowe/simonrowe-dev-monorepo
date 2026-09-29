package com.simonrowe.portfolio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonrowe.common.Image;

/**
 * A project as the public site sees it. A {@link ProjectStatus#COMING_SOON} project carries no
 * description, image or link at all — omitted from the JSON, not sent as null — so an
 * unfinished product's details cannot leak through the API before it launches.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicProject(
    String slug,
    String name,
    String tagline,
    ProjectStatus status,
    int accentHue,
    int displayOrder,
    String description,
    Image image,
    String liveUrl
) {
}
