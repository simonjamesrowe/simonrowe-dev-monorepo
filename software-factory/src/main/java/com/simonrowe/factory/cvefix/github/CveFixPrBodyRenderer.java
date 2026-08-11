package com.simonrowe.factory.cvefix.github;

import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.workflow.CveFixActivities.FixSummary;

/**
 * Pure string building for the CVE-fix pull request title, body, and give-up comment.
 *
 * <p>Kept separate from {@link CveFixPrGateway}, which only sends what it is given: the gateway
 * has no opinion on wording, so the title and body live here instead. {@link #giveUpComment} is
 * static and side-effect-free too, so the workflow may call it directly without breaking
 * determinism, and so the budget-exhaustion comment and the pull request body always describe the
 * same run consistently.
 */
public final class CveFixPrBodyRenderer {

  private static final String TITLE = "chore: bump dependencies with Dependency-Track findings";

  private CveFixPrBodyRenderer() {
  }

  /**
   * The pull request title, fixed prose for every CVE-fix run.
   *
   * @param summary the run's inputs; unused, since the title never varies
   * @return the fixed title
   */
  public static String title(final FixSummary summary) {
    return TITLE;
  }

  /**
   * The pull request body: the bumps made, then the components left unfixable with their
   * reasons, then the agent's own summary.
   *
   * @param summary the run's inputs
   * @return the rendered body
   */
  public static String body(final FixSummary summary) {
    StringBuilder body = new StringBuilder();
    appendBumps(body, summary);
    appendUnfixable(body, summary);
    body.append("\n## Agent summary\n").append(summary.agentSummary()).append('\n');
    return body.toString();
  }

  /**
   * The comment posted when the repair budget is exhausted and the pull request is left open for
   * a human, describing the same run as {@link #body}.
   *
   * @param summary the run's inputs
   * @param ciAttempts how many repair attempts were made before the budget ran out
   * @return the rendered comment
   */
  public static String giveUpComment(final FixSummary summary, final int ciAttempts) {
    StringBuilder comment = new StringBuilder();
    comment
        .append("CI did not go green after ")
        .append(ciAttempts)
        .append(" repair attempt(s). Leaving this pull request open for a human.\n\n");
    appendBumps(comment, summary);
    appendUnfixable(comment, summary);
    comment.append("\n## Agent summary\n").append(summary.agentSummary()).append('\n');
    return comment.toString();
  }

  private static void appendBumps(final StringBuilder builder, final FixSummary summary) {
    builder.append("## Dependency bumps\n");
    if (summary.bumpDescriptions().isEmpty()) {
      builder.append("- none\n");
      return;
    }
    for (String description : summary.bumpDescriptions()) {
      builder.append("- ").append(description).append('\n');
    }
  }

  private static void appendUnfixable(final StringBuilder builder, final FixSummary summary) {
    builder.append("\n## Left unfixable\n");
    if (summary.unfixable().isEmpty()) {
      builder.append("- none\n");
      return;
    }
    for (UnfixableComponent component : summary.unfixable()) {
      builder
          .append("- ")
          .append(component.purl())
          .append(": ")
          .append(component.reason())
          .append('\n');
    }
  }
}
