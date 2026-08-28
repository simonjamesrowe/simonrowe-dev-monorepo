package com.simonrowe.factory.cvefix.domain;

/** Outcome of one CVE-fix run, returned by the workflow method. */
public record CveFixResult(
    String workflowId,
    CveFixStatus status,
    String prUrl,
    int bumps,
    int unfixable,
    String detail,
    int filed,
    int updated,
    int suppressed,
    int regressed,
    java.util.List<String> issueUrls) {

  public CveFixResult {
    issueUrls = issueUrls == null ? java.util.List.of() : java.util.List.copyOf(issueUrls);
  }

  /** Backward-compatible constructor for historical tests and serialized callers. */
  public CveFixResult(
      final String workflowId,
      final CveFixStatus status,
      final String prUrl,
      final int bumps,
      final int unfixable,
      final String detail) {
    this(workflowId, status, prUrl, bumps, unfixable, detail, 0, 0, 0, 0,
        java.util.List.of());
  }
}
