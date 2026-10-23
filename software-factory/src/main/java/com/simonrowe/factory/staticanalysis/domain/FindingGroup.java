package com.simonrowe.factory.staticanalysis.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One problem worth one Linear ticket: either a failing quality-gate condition, or every open
 * new-code issue one rule raised in one module.
 *
 * <p><strong>Grouped by rule and module, not filed per issue and not filed as one report.</strong>
 * A ticket per issue would put forty "remove this unused import" tickets in Triage after one
 * careless merge. One consolidated report, as the CVE scan files, is the wrong unit for this
 * producer: these tickets exist to be handed to the ticket agent, which works one ticket per pull
 * request with three CI attempts, and "fix every finding in the repository" is not a change it can
 * be expected to land. One rule in one module is usually one idea applied a few times.
 *
 * <p>{@code issues} is always ordered by path then line by the compact constructor, so the
 * rendered ticket body is identical run to run while nothing changes. The renderer runs inside
 * workflow code, where a run-to-run difference is a determinism hazard rather than a cosmetic
 * one.
 *
 * @param kind what this group reports
 * @param module {@code backend}, {@code frontend}, {@code software-factory}, or
 *     {@code repository} for anything outside the three
 * @param rule the rule key for a {@link FindingKind#RULE} group, or the metric key for a
 *     {@link FindingKind#QUALITY_GATE} group
 * @param ruleName the rule's human name, or null when it could not be looked up
 * @param issues the open issues, empty for a quality-gate group
 * @param condition the failing condition, null for a rule group
 */
public record FindingGroup(
    FindingKind kind,
    String module,
    String rule,
    String ruleName,
    List<SonarIssue> issues,
    QualityGateCondition condition) {

  /** The module name used for paths outside the three known modules. */
  public static final String REPOSITORY = "repository";

  private static final List<String> MODULES = List.of("backend", "frontend", "software-factory");

  private static final Comparator<SonarIssue> BY_LOCATION =
      Comparator.comparing(SonarIssue::path, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparingInt(SonarIssue::line)
          .thenComparing(SonarIssue::key, Comparator.nullsLast(Comparator.naturalOrder()));

  /**
   * The filing order: quality-gate failures, then groups containing a defect, then by the worst
   * severity present, then the most issues, then module and rule so the order is total.
   */
  private static final Comparator<FindingGroup> FILING_ORDER =
      Comparator.comparingInt((FindingGroup group) -> group.kind() == FindingKind.QUALITY_GATE
              ? 0 : 1)
          .thenComparingInt(group -> group.hasDefect() ? 0 : 1)
          .thenComparingInt(FindingGroup::worstSeverityRank)
          .thenComparing(Comparator.comparingInt((FindingGroup group) -> group.issues().size())
              .reversed())
          .thenComparing(FindingGroup::module)
          .thenComparing(FindingGroup::rule);

  public FindingGroup {
    issues = issues == null ? List.of() : issues.stream().sorted(BY_LOCATION).toList();
  }

  /**
   * A group for one failing quality-gate condition.
   *
   * @param condition the failing condition
   * @return the group
   */
  public static FindingGroup qualityGate(final QualityGateCondition condition) {
    return new FindingGroup(FindingKind.QUALITY_GATE, REPOSITORY, condition.metricKey(), null,
        List.of(), condition);
  }

  /**
   * Keeps the issues worth a ticket, groups them by module and rule, and orders the groups for
   * filing alongside the quality-gate failures.
   *
   * <p>The floor applies to code smells only. A {@code BUG} or {@code VULNERABILITY} is filed at
   * any severity, and a group survives if any one of its issues does.
   *
   * @param issues every open new-code issue, in any order
   * @param failedConditions the quality gate's failing conditions
   * @param minimumSeverity the lowest severity filed for a code smell
   * @param ruleNames rule key to human name; a missing entry falls back to the key
   * @return the groups in filing order
   */
  public static List<FindingGroup> group(
      final List<SonarIssue> issues,
      final List<QualityGateCondition> failedConditions,
      final String minimumSeverity,
      final Map<String, String> ruleNames) {
    int floor = SonarSeverity.rank(minimumSeverity);
    Map<List<String>, List<SonarIssue>> byRule = new LinkedHashMap<>();
    for (SonarIssue issue : issues) {
      if (!issue.countsAsDefect() && SonarSeverity.rank(issue.severity()) > floor) {
        continue;
      }
      byRule
          .computeIfAbsent(List.of(moduleOf(issue.path()), issue.rule()),
              key -> new ArrayList<>())
          .add(issue);
    }
    List<FindingGroup> groups = new ArrayList<>();
    for (QualityGateCondition condition : failedConditions) {
      groups.add(qualityGate(condition));
    }
    for (Map.Entry<List<String>, List<SonarIssue>> entry : byRule.entrySet()) {
      String rule = entry.getKey().get(1);
      groups.add(new FindingGroup(FindingKind.RULE, entry.getKey().get(0), rule,
          ruleNames.get(rule), entry.getValue(), null));
    }
    groups.sort(FILING_ORDER);
    return List.copyOf(groups);
  }

  /**
   * The module a path belongs to, from its first segment.
   *
   * @param path a repository-relative path, which may be null
   * @return one of the three module names, or {@link #REPOSITORY}
   */
  public static String moduleOf(final String path) {
    if (path == null) {
      return REPOSITORY;
    }
    int slash = path.indexOf('/');
    String first = slash < 0 ? path : path.substring(0, slash);
    return MODULES.contains(first) ? first : REPOSITORY;
  }

  /**
   * The structured parts identifying this problem to the Linear sink.
   *
   * <p>Never the rule's name or any message: those are prose, and a rule renamed in a SonarCloud
   * release would otherwise fork every ticket filed under it. The leading discriminator keeps a
   * metric key and a rule key that happened to share text from ever colliding.
   *
   * @return the key parts
   */
  public List<String> keyParts() {
    return kind == FindingKind.QUALITY_GATE
        ? List.of("quality-gate", rule)
        : List.of("rule", module, rule);
  }

  /**
   * Whether any issue in this group is a bug or a vulnerability.
   *
   * @return true when a defect is present
   */
  public boolean hasDefect() {
    return issues.stream().anyMatch(SonarIssue::countsAsDefect);
  }

  private int worstSeverityRank() {
    return issues.stream()
        .mapToInt(issue -> SonarSeverity.rank(issue.severity()))
        .min()
        .orElse(SonarSeverity.rank(null));
  }
}
