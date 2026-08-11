package com.simonrowe.factory.cvefix.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every finding against one component, so a single bump can clear several advisories.
 *
 * <p>{@code vulnerabilityIds} is always sorted ascending and deduplicated by the compact
 * constructor, regardless of how the record is built — {@link #fingerprint()} depends on that
 * ordering, and any construction path is a fingerprint producer.
 */
public record ComponentFindings(
    String purl,
    String componentName,
    String componentVersion,
    List<String> vulnerabilityIds,
    List<Finding> findings) {

  public ComponentFindings {
    // Sorted here, not just by callers such as group(): fingerprint() and its Javadoc both
    // claim "sorted vulnerability ids" as a property of the type. Task 5 constructs this record
    // directly, so leaving the sort to a single caller would let an out-of-order construction
    // produce a fingerprint that never matches the stored one — silently and permanently
    // defeating the "don't re-attempt an unfixable CVE every night" suppression check.
    vulnerabilityIds =
        vulnerabilityIds == null
            ? List.of()
            : vulnerabilityIds.stream().sorted().distinct().toList();
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
              // Not sorted here: the compact constructor now sorts and dedupes
              // vulnerabilityIds itself, so every ComponentFindings is sorted regardless of
              // caller — sorting again here would be redundant.
              List<String> ids =
                  entry.getValue().stream().map(Finding::vulnerabilityId).toList();
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
