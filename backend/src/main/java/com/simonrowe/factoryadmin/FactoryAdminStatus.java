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
 */
public record FactoryAdminStatus(
    Instant fetchedAt,
    String backendCommit,
    String repository,
    boolean factoryReachable,
    boolean deployerReachable,
    List<FactoryInstanceStatus.ModuleStatus> modules) {

  public FactoryAdminStatus {
    modules = modules == null ? List.of() : List.copyOf(modules);
  }
}
