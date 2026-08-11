package com.simonrowe.factory.cvefix.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Every finding against one component, so a single bump can clear several advisories. */
public record ComponentFindings(
    String purl,
    String componentName,
    String componentVersion,
    List<String> vulnerabilityIds,
    List<Finding> findings) {

  public ComponentFindings {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /**
   * Groups findings by component PURL, returned sorted by PURL.
   *
   * <p>The order is the sort, not the input order: Dependency-Track's array order is not stable,
   * so a run-to-run-stable prompt and a stable set of grouped components need an explicit sort.
   * {@code LinkedHashMap} only keeps the intermediate grouping deterministic while it is built.
   */
  public static List<ComponentFindings> group(final List<Finding> findings) {
    Map<String, List<Finding>> byPurl =
        findings.stream()
            .collect(
                Collectors.groupingBy(Finding::purl, LinkedHashMap::new, Collectors.toList()));
    return byPurl.entrySet().stream()
        .map(
            entry -> {
              Finding first = entry.getValue().get(0);
              List<String> ids =
                  entry.getValue().stream()
                      .map(Finding::vulnerabilityId)
                      .distinct()
                      .sorted()
                      .toList();
              return new ComponentFindings(
                  entry.getKey(), first.componentName(), first.componentVersion(), ids,
                  entry.getValue());
            })
        .sorted(Comparator.comparing(ComponentFindings::purl))
        .toList();
  }

  /**
   * Suppression key: the PURL plus its sorted vulnerability ids. Sorting is essential —
   * Dependency-Track's array order is not stable, and an unsorted key would make every run look
   * like new information and defeat the suppression entirely.
   */
  public String fingerprint() {
    return purl + "|" + String.join(",", vulnerabilityIds);
  }
}
