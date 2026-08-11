package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/** What one agent attempt produced. */
public record FixProposal(List<Bump> bumps, List<UnfixableComponent> unfixable, String summary) {

  public FixProposal {
    bumps = bumps == null ? List.of() : List.copyOf(bumps);
    unfixable = unfixable == null ? List.of() : List.copyOf(unfixable);
  }

  /** True when the agent changed nothing, so there is no pull request to open. */
  public boolean isEmpty() {
    return bumps.isEmpty();
  }
}
