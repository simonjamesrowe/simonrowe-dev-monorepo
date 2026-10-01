package com.simonrowe.portfolio;

/**
 * A titled paragraph with an optional picture: a numbered highlight on a project's overview,
 * or one of the points under its statement. {@code imageUrl} is a site path or an https URL,
 * so it can name a media-library upload or an asset shipped in the frontend bundle.
 */
public record ProjectHighlight(String title, String text, String imageUrl, String imageAlt) {
}
