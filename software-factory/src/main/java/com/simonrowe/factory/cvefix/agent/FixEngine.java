package com.simonrowe.factory.cvefix.agent;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.FixProposal;
import com.simonrowe.factory.git.RepositoryWorkspace;
import java.util.List;
import java.util.function.Consumer;

/** Proposes dependency bumps for a set of findings by editing the checkout in place. */
public interface FixEngine {

  /**
   * Edits the manifests in {@code workspace} and describes what it did.
   *
   * @param workspace the repository checkout to edit in place
   * @param components the findings to address, grouped by component
   * @param failureContext CI failure output from the previous attempt, or null on the first
   *     attempt
   * @param rejectedBumps every bump an earlier attempt on this run already pushed and CI
   *     rejected, empty on the first attempt. Load-bearing: each attempt edits a fresh clone of
   *     the default branch, so the manifests never show what the last attempt chose.
   * @param heartbeat receives progress messages while the agent runs
   * @return what the agent changed and what it could not fix
   */
  FixProposal propose(
      RepositoryWorkspace workspace,
      List<ComponentFindings> components,
      String failureContext,
      List<String> rejectedBumps,
      Consumer<String> heartbeat);
}
