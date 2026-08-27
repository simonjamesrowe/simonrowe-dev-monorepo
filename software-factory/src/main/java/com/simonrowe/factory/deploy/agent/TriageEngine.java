package com.simonrowe.factory.deploy.agent;

import com.simonrowe.factory.deploy.workflow.DeployActivities;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Explains a failed deploy from captured evidence. */
public interface TriageEngine {

  /**
   * Diagnoses a failed deploy.
   *
   * @param evidenceDirectory a directory of captured text files — logs, container states, the
   *     failing phase's output and the commit range
   * @param heartbeat called periodically while the agent runs
   * @return the diagnosis
   */
  DeployActivities.Triage diagnose(Path evidenceDirectory, Consumer<String> heartbeat);
}
