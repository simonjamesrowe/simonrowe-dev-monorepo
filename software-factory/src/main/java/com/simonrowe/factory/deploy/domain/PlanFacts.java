package com.simonrowe.factory.deploy.domain;

import java.util.List;

/**
 * What {@code restart-prod.sh plan} found: the facts a {@link DeployPlan} is decided from.
 *
 * <p>Facts only, no decision. The script reports what differs between the running stack and the
 * commit being deployed; which kind of deploy that makes is policy, and lives in {@link
 * DeployPlan#decide}, where it can be tested without a shell.
 *
 * @param changedImages services whose image for the commit is not the image their container runs
 * @param changedConfig services whose compose configuration hash differs from their container's
 * @param changedHostFiles bind-mounted files under {@code config/} changed since the previously
 *     deployed commit
 * @param readable false when the script could not tell, in which case nothing else here can be
 *     trusted and the deploy falls back to deploying everything
 * @param detail one line for the run record
 */
public record PlanFacts(
    List<String> changedImages,
    List<String> changedConfig,
    List<String> changedHostFiles,
    boolean readable,
    String detail) {

  public PlanFacts {
    changedImages = changedImages == null ? List.of() : List.copyOf(changedImages);
    changedConfig = changedConfig == null ? List.of() : List.copyOf(changedConfig);
    changedHostFiles = changedHostFiles == null ? List.of() : List.copyOf(changedHostFiles);
    detail = detail == null ? "" : detail;
  }

  /**
   * Facts that could not be established.
   *
   * @param reason why, for the run record
   * @return unreadable facts
   */
  public static PlanFacts unreadable(final String reason) {
    return new PlanFacts(List.of(), List.of(), List.of(), false, reason);
  }
}
