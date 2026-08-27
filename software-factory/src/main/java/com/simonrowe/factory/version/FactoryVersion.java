package com.simonrowe.factory.version;

import java.time.Instant;

/**
 * This container's version, as served to the backend for the public status page.
 *
 * @param commit the full commit SHA, or {@code unknown}
 * @param shortCommit the seven-character SHA, or {@code dev}
 * @param commitSubject the commit subject line, or null
 * @param commitTime when the commit was authored, or null when unknown
 * @param startedAt when this JVM started
 */
public record FactoryVersion(
    String commit,
    String shortCommit,
    String commitSubject,
    Instant commitTime,
    Instant startedAt) {
}
