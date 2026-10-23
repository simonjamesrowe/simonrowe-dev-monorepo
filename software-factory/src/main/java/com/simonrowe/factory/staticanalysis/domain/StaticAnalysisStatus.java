package com.simonrowe.factory.staticanalysis.domain;

/**
 * How a scan ended.
 *
 * <p>{@code NO_FINDINGS} and {@code SOURCE_UNHEALTHY} are deliberately separate, for the reason
 * {@link AnalysisHealth} gives: a stale analysis and a clean one read identically unless something
 * checks.
 */
public enum StaticAnalysisStatus {
  RUNNING,
  COMPLETED,
  NO_FINDINGS,
  SOURCE_UNHEALTHY,
  FAILED
}
