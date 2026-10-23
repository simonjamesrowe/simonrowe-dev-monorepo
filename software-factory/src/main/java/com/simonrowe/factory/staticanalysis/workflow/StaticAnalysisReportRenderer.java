package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.FindingKind;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import com.simonrowe.factory.staticanalysis.domain.SonarProject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Renders static-analysis tickets as Markdown.
 *
 * <p>Pure and deterministic by contract, because the workflow calls it: no clocks, no randomness,
 * no iteration over unordered collections. It relies on {@link FindingGroup} having ordered its
 * issues.
 *
 * <p>The body is written for the ticket agent as much as for a person. A ticket here is meant to
 * be approved with {@code factory:agent} and fixed unattended, so it names every location, links
 * the rule, and says plainly that silencing the rule is not a fix - the agent's push is checked
 * for exactly that, and a pull request that adds {@code NOSONAR} is never auto-merged.
 */
public final class StaticAnalysisReportRenderer {

  /**
   * Issues listed per ticket. Past this the body says how many more there are, rather than
   * growing without bound: a rule that fired two hundred times is one idea, and the agent needs
   * the pattern more than the full list.
   */
  static final int MAX_LISTED_ISSUES = 50;

  private static final Map<String, String> METRIC_NAMES =
      Map.of(
          "new_coverage", "coverage on new code",
          "new_duplicated_lines_density", "duplicated lines on new code",
          "new_reliability_rating", "reliability rating on new code",
          "new_security_rating", "security rating on new code",
          "new_maintainability_rating", "maintainability rating on new code",
          "new_security_hotspots_reviewed", "security hotspots reviewed on new code");

  private StaticAnalysisReportRenderer() {
  }

  /**
   * The ticket title. Used only when the ticket is created, so it may be prose.
   *
   * @param group the finding group
   * @return the title
   */
  public static String title(final FindingGroup group) {
    if (group.kind() == FindingKind.QUALITY_GATE) {
      return "Static analysis: quality gate failing on " + metricName(group.rule());
    }
    String name = group.ruleName() == null || group.ruleName().isBlank()
        ? group.rule()
        : group.ruleName();
    return "Static analysis (%s): %s".formatted(group.module(), name);
  }

  /**
   * The ticket body.
   *
   * @param group the finding group
   * @param project where the analysis lives
   * @param health the verdict on the analysis read, which names its revision
   * @return the Markdown body
   */
  public static String body(
      final FindingGroup group, final SonarProject project, final AnalysisHealth health) {
    StringBuilder markdown = new StringBuilder();
    if (group.kind() == FindingKind.QUALITY_GATE) {
      QualityGateCondition condition = group.condition();
      markdown
          .append("SonarCloud's quality gate on `").append(project.branch())
          .append("` is failing on **").append(metricName(group.rule())).append("**: ")
          .append(condition.actualValue()).append(", where the gate requires ")
          .append(comparatorPhrase(condition.comparator())).append(' ')
          .append(condition.errorThreshold()).append(".\n\n")
          .append("Measured on new code since the project's new-code baseline. ")
          .append("[Open the project in SonarCloud](")
          .append(projectUrl(project)).append(").\n");
    } else {
      markdown
          .append("SonarCloud reports **").append(group.issues().size())
          .append(" open issue(s)** from rule [`").append(group.rule()).append("`](")
          .append(ruleUrl(project, group.rule())).append(") in `").append(group.module())
          .append("`, on new code on `").append(project.branch()).append("`.\n\n");
      int listed = 0;
      for (SonarIssue issue : group.issues()) {
        if (listed == MAX_LISTED_ISSUES) {
          markdown.append("- ... and ").append(group.issues().size() - listed)
              .append(" more in SonarCloud\n");
          break;
        }
        markdown.append("- `").append(issue.path());
        if (issue.line() > 0) {
          markdown.append(':').append(issue.line());
        }
        markdown.append("` (").append(issue.severity()).append(' ')
            .append(typeName(issue.type())).append("): ").append(issue.message())
            .append(" [open](").append(issueUrl(project, issue.key())).append(")\n");
        listed++;
      }
    }
    markdown.append("""

        **Fixing it.** Change the code so the rule no longer applies, and keep the change to \
        this one problem. Do not silence it: `NOSONAR`, `@SuppressWarnings`, an `eslint-disable` \
        or a new exclusion is not a fix, and the ticket agent's pull requests are never \
        auto-merged when they add one. If SonarCloud is wrong, mark the issue as a false \
        positive in SonarCloud and close this ticket.

        Read from the analysis of `%s`. A nightly scan rewrites this description while the \
        problem is still reported, and closes the ticket once a fresh analysis of `%s` no \
        longer reports it.
        """.formatted(health.analysedRevision(), project.branch()));
    return markdown.toString();
  }

  /**
   * One line naming this occurrence, recorded by the sink.
   *
   * @param group the finding group
   * @param runId the scan's run id
   * @return the line
   */
  public static String occurrenceDetail(final FindingGroup group, final String runId) {
    return group.kind() == FindingKind.QUALITY_GATE
        ? "scan %s: quality gate failing on %s".formatted(runId, group.rule())
        : "scan %s: %s issue(s) from %s in %s"
            .formatted(runId, group.issues().size(), group.rule(), group.module());
  }

  /**
   * The title of the ticket saying the module cannot trust what it reads.
   *
   * @param health the verdict
   * @return the title
   */
  public static String sourceHealthTitle(final AnalysisHealth health) {
    return switch (health.status()) {
      case STALE -> "Static analysis: SonarCloud has stopped analysing main";
      case NO_ANALYSIS -> "Static analysis: SonarCloud has no analysis of main";
      default -> "Static analysis: SonarCloud cannot be read";
    };
  }

  /**
   * The body of that ticket.
   *
   * @param health the verdict
   * @param project where the analysis lives
   * @return the Markdown body
   */
  public static String sourceHealthBody(final AnalysisHealth health, final SonarProject project) {
    return """
        The nightly static-analysis scan could not trust SonarCloud's view of `%s`, so it filed \
        nothing and closed nothing.

        %s

        Until this is fixed, findings introduced since the last analysis reach no ticket at all. \
        Analysis runs only on pushes to `%s` (the `Static Analysis` job in `ci.yml`), so a red \
        build there, an expired `SONAR_TOKEN`, or SonarCloud's Automatic Analysis being switched \
        back on all end here. See `docs/runbooks/static-analysis.md`. \
        [Project in SonarCloud](%s).

        This ticket closes itself after the next scan that finds a current analysis.
        """.formatted(project.branch(), health.evidence(), project.branch(), projectUrl(project));
  }

  /**
   * The comment posted on a ticket as the absence sweep closes it.
   *
   * @param resolveAfter how long the finding had gone unreported
   * @param runId the scan's run id
   * @param health the verdict on the analysis read, which names its revision
   * @return the comment
   */
  public static String resolutionComment(
      final Duration resolveAfter, final String runId, final AnalysisHealth health) {
    return """
        SonarCloud's analysis of `%s` no longer reports this, and nothing has reported it for \
        at least %sh, so scan %s has closed it as resolved. If it comes back, the next scan files \
        a new ticket linked to this one."""
        .formatted(health.analysedRevision(), resolveAfter.toHours(), runId);
  }

  private static String metricName(final String metricKey) {
    return METRIC_NAMES.getOrDefault(metricKey, "`" + metricKey + "`");
  }

  private static String comparatorPhrase(final String comparator) {
    return switch (comparator) {
      case "LT" -> "at least";
      case "GT" -> "at most";
      default -> comparator;
    };
  }

  private static String typeName(final String type) {
    return switch (type) {
      case "BUG" -> "bug";
      case "VULNERABILITY" -> "vulnerability";
      default -> "code smell";
    };
  }

  private static String projectUrl(final SonarProject project) {
    return "%s/summary/new_code?id=%s&branch=%s"
        .formatted(project.sonarBaseUrl(), encoded(project.projectKey()),
            encoded(project.branch()));
  }

  private static String issueUrl(final SonarProject project, final String issueKey) {
    return "%s/project/issues?id=%s&branch=%s&open=%s"
        .formatted(project.sonarBaseUrl(), encoded(project.projectKey()),
            encoded(project.branch()), encoded(issueKey));
  }

  private static String ruleUrl(final SonarProject project, final String rule) {
    return "%s/organizations/%s/rules?open=%s&rule_key=%s"
        .formatted(project.sonarBaseUrl(), encoded(project.organization()), encoded(rule),
            encoded(rule));
  }

  private static String encoded(final String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
