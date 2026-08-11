package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Result of distillation: status and any proposed guidance PRs. */
public record DistillationOutcome(DistillationStatus status, List<String> prUrls, String detail) {

  public DistillationOutcome {
    prUrls = prUrls == null ? List.of() : List.copyOf(prUrls);
  }
}
