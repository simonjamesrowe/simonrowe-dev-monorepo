package com.simonrowe.factory.codereview.domain;

import java.util.List;

/** Structured, validated output from a review engine. */
public record ReviewReport(String summary, Verdict verdict, List<ReviewFinding> findings) {

  public ReviewReport {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }
}
