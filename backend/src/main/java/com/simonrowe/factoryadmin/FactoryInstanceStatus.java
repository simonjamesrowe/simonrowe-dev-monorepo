package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/** Wire shape of the internal factory status endpoint. */
public record FactoryInstanceStatus(
    String container, Instant fetchedAt, List<ModuleStatus> modules) {

  public FactoryInstanceStatus {
    modules = modules == null ? List.of() : List.copyOf(modules);
  }

  public record ModuleStatus(
      String key,
      String displayName,
      boolean configured,
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
