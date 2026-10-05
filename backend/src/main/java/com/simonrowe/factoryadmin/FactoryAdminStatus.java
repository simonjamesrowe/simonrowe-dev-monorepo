package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/**
 * Browser-safe aggregate returned by the role-protected backend.
 *
 * <p>{@code repository} is included so the console can name the repository its pull-request
 * actions operate on. The browser cannot otherwise know it — the owner and repository are fixed
 * server-side and never sent by the client — and a hardcoded guess in the frontend would quietly
 * start lying the moment the configuration changed.
 *
 * <p>{@code serviceCommits} lets the console name the redeploy commit before it is asked for:
 * the newest of these and the bundle's own commit, by the rule in {@link RedeployTarget}.
 */
public record FactoryAdminStatus(
    Instant fetchedAt,
    String backendCommit,
    List<ServiceCommit> serviceCommits,
    String repository,
    boolean factoryReachable,
    boolean deployerReachable,
    List<FactoryInstanceStatus.ModuleStatus> modules) {

  public FactoryAdminStatus {
    serviceCommits = serviceCommits == null ? List.of() : List.copyOf(serviceCommits);
    modules = modules == null ? List.of() : List.copyOf(modules);
  }
}
