package com.simonrowe.factory.cvefix.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every finding against one component in one Dependency-Track project, so a single bump can clear
 * several advisories and the reader can tell which manifest to edit.
 *
 * <p>{@code findings} is always ordered most severe first, then by vulnerability id, by the
 * compact constructor — so the ordering holds for every construction path, not only for
 * {@link #group(List)}.
 */
public record ComponentFindings(
    String project,
    String purl,
    String componentName,
    String componentVersion,
    List<String> vulnerabilityIds,
    List<Finding> findings) {

  private static final Comparator<Finding> BY_SEVERITY =
      Comparator.comparingInt((Finding finding) -> Severity.rank(finding.severity()))
          .thenComparing(Finding::vulnerabilityId, Comparator.nullsLast(Comparator.naturalOrder()));

  public ComponentFindings {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
    findings = findings == null ? List.of() : findings.stream().sorted(BY_SEVERITY).toList();
  }

  /**
   * Groups findings by {@code (project, purl)}.
   *
   * <p>Ordering is explicit at all three levels, because Dependency-Track's array order is not
   * stable and this list is rendered inside workflow code, where a run-to-run difference is a
   * determinism hazard rather than a cosmetic one:
   *
   * <ul>
   *   <li>Projects keep first-appearance order, which is the configured
   *       {@code factory.cvefix.dependency-track.projects} order because
   *       {@code DependencyTrackClient} iterates that list. Ordering projects by severity instead
   *       would reshuffle the report's headings run to run and make every Linear comment look
   *       like the whole report had changed.
   *   <li>Components within a project lead with their most severe finding, then PURL. Severity
   *       alone is not a total order — many components share {@code HIGH} — so PURL is the
   *       tiebreak.
   *   <li>Advisories within a component are ordered by the compact constructor above.
   * </ul>
   *
   * @param findings every finding across every in-scope project, in any order
   * @return the grouped components, ordered as described
   */
  public static List<ComponentFindings> group(final List<Finding> findings) {
    Map<String, List<Finding>> byProject = new LinkedHashMap<>();
    for (Finding finding : findings) {
      byProject.computeIfAbsent(finding.project(), key -> new ArrayList<>()).add(finding);
    }
    List<ComponentFindings> grouped = new ArrayList<>();
    for (Map.Entry<String, List<Finding>> project : byProject.entrySet()) {
      Map<String, List<Finding>> byPurl = new LinkedHashMap<>();
      for (Finding finding : project.getValue()) {
        byPurl.computeIfAbsent(finding.purl(), key -> new ArrayList<>()).add(finding);
      }
      List<ComponentFindings> components = new ArrayList<>();
      for (Map.Entry<String, List<Finding>> entry : byPurl.entrySet()) {
        // Sort before reading componentName/version and the id list, so every derived value
        // agrees with the order the constructor will settle on.
        List<Finding> sorted = entry.getValue().stream().sorted(BY_SEVERITY).toList();
        Finding worst = sorted.get(0);
        components.add(
            new ComponentFindings(
                project.getKey(),
                entry.getKey(),
                worst.componentName(),
                worst.componentVersion(),
                sorted.stream().map(Finding::vulnerabilityId).toList(),
                sorted));
      }
      components.sort(
          Comparator.comparingInt(ComponentFindings::worstSeverityRank)
              .thenComparing(ComponentFindings::purl));
      grouped.addAll(components);
    }
    return List.copyOf(grouped);
  }

  /**
   * The rank of this component's most severe finding, for ordering components within a project.
   *
   * @return the best (lowest) severity rank present, or the unranked value when there are none
   */
  private int worstSeverityRank() {
    return findings.isEmpty()
        ? Severity.rank(null)
        : Severity.rank(findings.get(0).severity());
  }
}
