package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the consolidated vulnerability report as Markdown.
 *
 * <p>Pure and deterministic by contract, because {@code CveFixWorkflowImpl} calls it from workflow
 * code: no clocks, no randomness, and no iteration over unordered collections. It relies entirely
 * on {@link ComponentFindings#group(java.util.List)} having already established the ordering, and
 * emits a project heading whenever the project changes — which is why it must be handed that
 * list unmodified.
 */
public final class CveReportRenderer {

  /** Heading used for a component whose project is absent, as on replay of older history. */
  private static final String UNKNOWN_PROJECT = "(unknown project)";

  /**
   * Placeholder for a finding whose vulnerability id is absent, as on replay of older history
   * that {@link ComponentFindings} deliberately tolerates.
   */
  private static final String UNIDENTIFIED_ADVISORY = "(unidentified advisory)";

  private CveReportRenderer() {
  }

  /**
   * Renders the report body.
   *
   * @param components every component with findings, ordered by project then severity then PURL
   * @param findingsSeen the total number of individual findings behind those components
   * @return the Markdown body
   */
  public static String report(
      final List<ComponentFindings> components, final int findingsSeen) {
    List<String> projects = projectsIn(components);
    StringBuilder markdown = new StringBuilder()
        .append("Dependency-Track currently reports **").append(findingsSeen)
        .append(" finding(s)** across **").append(components.size())
        .append(" component(s)** in **").append(projects.size())
        .append(" project(s)** (").append(quoted(projects))
        .append("). This is the repository's consolidated vulnerability report.\n");

    String currentProject = null;
    for (ComponentFindings component : components) {
      String project = nameOf(component);
      if (!project.equals(currentProject)) {
        markdown.append("\n## ").append(project).append("\n");
        currentProject = project;
      }
      markdown.append("\n### ").append(component.componentName()).append(" `")
          .append(component.componentVersion()).append("`\n\n")
          .append("- **Package:** `").append(component.purl()).append("`\n")
          .append("- **Advisories:** ")
          .append(joinedAdvisoryIds(component.vulnerabilityIds())).append("\n\n");
      for (Finding finding : component.findings()) {
        markdown.append("- **").append(idOf(finding.vulnerabilityId())).append("** (")
            .append(finding.severity()).append(")");
        if (finding.recommendation() != null && !finding.recommendation().isBlank()) {
          markdown.append(": ").append(finding.recommendation());
        }
        markdown.append('\n');
      }
    }

    markdown.append("\nA future Linear-triggered repair agent will own remediation; this scan does "
        + "not modify the repository.\n");
    return markdown.toString();
  }

  private static List<String> projectsIn(final List<ComponentFindings> components) {
    // Deliberately not a Set: the report's project order is the input order, and a hash-ordered
    // set would put the header's project list out of step with the headings below it.
    List<String> projects = new ArrayList<>();
    for (ComponentFindings component : components) {
      String name = nameOf(component);
      if (!projects.contains(name)) {
        projects.add(name);
      }
    }
    return projects;
  }

  private static String nameOf(final ComponentFindings component) {
    String project = component.project();
    return project == null || project.isBlank() ? UNKNOWN_PROJECT : project;
  }

  private static String quoted(final List<String> projects) {
    return String.join(", ", projects.stream().map(name -> "`" + name + "`").toList());
  }

  private static String joinedAdvisoryIds(final List<String> vulnerabilityIds) {
    return String.join(", ", vulnerabilityIds.stream().map(CveReportRenderer::idOf).toList());
  }

  private static String idOf(final String vulnerabilityId) {
    return vulnerabilityId == null || vulnerabilityId.isBlank()
        ? UNIDENTIFIED_ADVISORY
        : vulnerabilityId;
  }
}
