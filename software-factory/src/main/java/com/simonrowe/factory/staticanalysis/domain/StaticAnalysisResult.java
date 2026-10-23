package com.simonrowe.factory.staticanalysis.domain;

import java.util.List;

/**
 * Outcome of one scan, returned by the workflow method.
 *
 * @param status how the scan ended
 * @param health the analysis verdict
 * @param issuesSeen open new-code issues read, before the severity floor
 * @param groupsFound finding groups produced, before the per-run cap
 * @param groupsDropped finding groups the cap left unfiled
 * @param issueUrls Linear issues filed or refreshed by this run
 * @param detail free-text summary
 * @param resolvedUrls Linear issues this run closed because the analysis no longer reports them
 */
public record StaticAnalysisResult(
    StaticAnalysisStatus status,
    AnalysisHealth health,
    int issuesSeen,
    int groupsFound,
    int groupsDropped,
    List<String> issueUrls,
    String detail,
    List<String> resolvedUrls) {

  public StaticAnalysisResult {
    issueUrls = issueUrls == null ? List.of() : List.copyOf(issueUrls);
    resolvedUrls = resolvedUrls == null ? List.of() : List.copyOf(resolvedUrls);
  }
}
