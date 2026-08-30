package com.simonrowe.factory.cvefix.domain;

/**
 * One Dependency-Track finding: a vulnerability against a specific component version, in a
 * specific Dependency-Track project.
 *
 * <p>{@code project} is the Dependency-Track project name verbatim, such as
 * {@code simonrowe-dev/backend}, so it matches what an operator would search for in the
 * Dependency-Track UI. It is deliberately a carried field rather than something inferred from the
 * PURL's ecosystem: {@code pkg:maven} happens to mean the backend only while exactly one maven
 * project is in scope.
 *
 * <p>{@code recommendation} is the advisory's free-text prose and is frequently empty.
 * Dependency-Track exposes no fixed-version field, so the target version is the reader's to
 * determine.
 */
public record Finding(
    String project,
    String purl,
    String componentName,
    String componentVersion,
    String vulnerabilityId,
    String severity,
    String recommendation) {
}
