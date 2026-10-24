package com.simonrowe.factory.cvefix.domain;

import com.simonrowe.factory.cvefix.osv.OsvClient;
import java.util.List;

/**
 * One dependency bump the factory can make on its own: a backend Maven component or a frontend
 * npm package whose advisories all have a fixed release in the same major version.
 *
 * @param project the Dependency-Track project, e.g. {@code simonrowe-dev/backend}
 * @param purl the component's PURL as Dependency-Track reports it, version included
 * @param coordinate {@code group:artifact} for Maven, the package name for npm
 * @param currentVersion the version in the SBOM
 * @param targetVersion the lowest version OSV says fixes every advisory against it
 * @param advisoryIds Dependency-Track's advisory ids, most severe first
 * @param severity the most severe finding's severity
 * @param ecosystem {@link OsvClient#MAVEN} or {@link OsvClient#NPM}
 */
public record RepairPlan(
    String project,
    String purl,
    String coordinate,
    String currentVersion,
    String targetVersion,
    List<String> advisoryIds,
    String severity,
    String ecosystem) {

  /**
   * Normalises the lists, and reads a plan without an ecosystem as Maven. A plan recorded in a
   * Temporal history before npm repairs existed has none, and every one of those was Maven.
   */
  public RepairPlan {
    advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
    ecosystem = ecosystem == null || ecosystem.isBlank() ? OsvClient.MAVEN : ecosystem;
  }

  /** A Maven plan, as every plan was before npm repairs. */
  public RepairPlan(
      final String project,
      final String purl,
      final String coordinate,
      final String currentVersion,
      final String targetVersion,
      final List<String> advisoryIds,
      final String severity) {
    this(project, purl, coordinate, currentVersion, targetVersion, advisoryIds, severity, null);
  }

  /** Whether the factory regenerates the npm lockfile for it rather than the agent editing. */
  public boolean npm() {
    return OsvClient.NPM.equals(ecosystem);
  }
}
