package com.simonrowe.factory.cvefix.domain;

/**
 * One Dependency-Track finding: a vulnerability against a specific component version.
 *
 * <p>{@code recommendation} is the advisory's free-text prose and is frequently empty.
 * Dependency-Track exposes no fixed-version field, so the target version is the agent's to
 * determine.
 */
public record Finding(
    String purl,
    String componentName,
    String componentVersion,
    String vulnerabilityId,
    String severity,
    String recommendation) {
}
