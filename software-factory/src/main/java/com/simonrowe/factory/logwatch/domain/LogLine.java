package com.simonrowe.factory.logwatch.domain;

import java.time.Instant;

/**
 * One log line as read from Loki, after severity detection.
 *
 * @param container the {@code container} label the line was shipped under
 * @param timestamp when the line was emitted
 * @param severity the detected severity; lines matching no detector never reach this type
 * @param raw the line as shipped, before signature normalisation
 */
public record LogLine(String container, Instant timestamp, Severity severity, String raw) {
}
