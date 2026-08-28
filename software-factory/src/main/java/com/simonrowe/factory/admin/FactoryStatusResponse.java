package com.simonrowe.factory.admin;

import java.time.Instant;
import java.util.List;

/** Safe, read-only factory status returned only on the internal container network. */
public record FactoryStatusResponse(
    String container, Instant fetchedAt, List<ModuleStatus> modules) {

  public FactoryStatusResponse {
    modules = modules == null ? List.of() : List.copyOf(modules);
  }

  /** One module's configuration and Temporal readiness. */
  public record ModuleStatus(
      String key,
      String displayName,
      Boolean configured,
      String taskQueue,
      Integer workflowPollers,
      Integer activityPollers,
      String trigger,
      ScheduleStatus schedule,
      List<String> missingPrerequisites,
      boolean ready,
      String diagnostic) {

    public ModuleStatus {
      missingPrerequisites =
          missingPrerequisites == null ? List.of() : List.copyOf(missingPrerequisites);
    }
  }

  /** Temporal schedule facts, absent for event-driven modules. */
  public record ScheduleStatus(
      String scheduleId,
      boolean exists,
      Boolean paused,
      String overlapPolicy,
      Instant previousActionAt,
      Instant nextActionAt,
      int runningActions,
      String diagnostic) {
  }
}
