package com.simonrowe.factory.logwatch.domain;

/**
 * Where a scan has got to.
 *
 * <p>{@code CHECKING_SOURCE} comes first, before any reading. A scan that cannot establish its
 * source is alive must not spend a language-model call, and must not file signature tickets that
 * may be artefacts of a partial read.
 */
public enum LogWatchPhase {
  ACCEPTED,
  CHECKING_SOURCE,
  READING,
  GROUPING,
  WRITING_UP,
  FILING,
  DONE
}
