package com.simonrowe.portfolio;

/**
 * A sub-page of a project, served at {@code /portfolio/{project}/{slug}}: a title for the tab,
 * an optional hint shown beside it, a summary for the teaser on the overview, and a markdown
 * body.
 */
public record ProjectPage(String slug, String title, String navHint, String summary, String body) {
}
