package com.simonrowe.factory.logwatch.domain;

/**
 * Queryable progress snapshot.
 *
 * <p>Exactly three fields, matching every other factory module's {@code progress} query shape
 * {@code {phase, detail, <one module-specific field>}}. The console reads it as an untyped
 * {@code JsonNode} because Temporal's {@code JacksonJsonPayloadConverter} does not disable
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a typed read of one module's record throws on another's.
 *
 * @param phase where the run has got to
 * @param detail free-text diagnostics
 * @param signaturesFound how many distinct problems have been grouped so far; may be null
 */
public record LogWatchProgress(LogWatchPhase phase, String detail, Integer signaturesFound) {

  /** The state a run reports before its first activity completes. */
  public static LogWatchProgress accepted() {
    return new LogWatchProgress(LogWatchPhase.ACCEPTED, "Accepted", null);
  }
}
