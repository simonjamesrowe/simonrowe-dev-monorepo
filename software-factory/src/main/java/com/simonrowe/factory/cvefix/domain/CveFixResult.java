package com.simonrowe.factory.cvefix.domain;

/** Outcome of one CVE-fix run, returned by the workflow method. */
public record CveFixResult(
    String workflowId,
    CveFixStatus status,
    String prUrl,
    int bumps,
    int unfixable,
    String detail) {
}
