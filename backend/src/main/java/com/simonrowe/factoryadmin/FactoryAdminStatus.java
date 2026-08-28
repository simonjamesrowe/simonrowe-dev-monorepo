package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/** Browser-safe aggregate returned by the role-protected backend. */
public record FactoryAdminStatus(
    Instant fetchedAt,
    String backendCommit,
    boolean factoryReachable,
    boolean deployerReachable,
    List<FactoryInstanceStatus.ModuleStatus> modules) {

  public FactoryAdminStatus {
    modules = modules == null ? List.of() : List.copyOf(modules);
  }
}
