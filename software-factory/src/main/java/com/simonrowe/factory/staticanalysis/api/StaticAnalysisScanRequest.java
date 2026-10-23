package com.simonrowe.factory.staticanalysis.api;

/**
 * The manual-scan request body. Every field is optional.
 *
 * @param dryRun when true, nothing is created, commented on or closed in Linear
 */
public record StaticAnalysisScanRequest(Boolean dryRun) {

  /** Whether this request asked for a dry run, treating an absent flag as false. */
  public boolean isDryRun() {
    return Boolean.TRUE.equals(dryRun);
  }
}
