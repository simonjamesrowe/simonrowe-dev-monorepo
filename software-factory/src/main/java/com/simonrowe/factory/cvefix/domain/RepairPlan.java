package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/**
 * One dependency bump the ticket agent can make on its own: a backend Maven component whose
 * advisories all have a fixed release in the same major version.
 *
 * @param project the Dependency-Track project, e.g. {@code simonrowe-dev/backend}
 * @param purl the component's PURL as Dependency-Track reports it, version included
 * @param coordinate {@code group:artifact}
 * @param currentVersion the version in the SBOM
 * @param targetVersion the lowest version OSV says fixes every advisory against it
 * @param advisoryIds Dependency-Track's advisory ids, most severe first
 * @param severity the most severe finding's severity
 */
public record RepairPlan(
    String project,
    String purl,
    String coordinate,
    String currentVersion,
    String targetVersion,
    List<String> advisoryIds,
    String severity) {

  public RepairPlan {
    advisoryIds = advisoryIds == null ? List.of() : List.copyOf(advisoryIds);
  }
}
