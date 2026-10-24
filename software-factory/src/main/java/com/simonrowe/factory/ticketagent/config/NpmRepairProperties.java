package com.simonrowe.factory.ticketagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the ticket agent has GitHub Actions regenerate {@code frontend/package-lock.json} for an npm
 * vulnerability repair.
 *
 * <p>A separate record from {@link TicketAgentProperties}, so adding it touched no existing
 * constructor call.
 *
 * @param workflow the workflow file under {@code .github/workflows/}
 * @param ref the branch the workflow runs on. Always the protected default branch: the workflow
 *     definition that runs is then one the ruleset guarded, never a branch's copy
 * @param runWait how long to wait for the run to finish
 * @param pollInterval how often to read the run while waiting
 * @param maxArtifactBytes the largest artifact download accepted
 */
@ConfigurationProperties("factory.ticket-agent.npm")
public record NpmRepairProperties(
    String workflow,
    String ref,
    Duration runWait,
    Duration pollInterval,
    long maxArtifactBytes) {

  public NpmRepairProperties {
    workflow = workflow == null || workflow.isBlank() ? "npm-lockfile-repair.yml" : workflow;
    ref = ref == null || ref.isBlank() ? "main" : ref;
    runWait = runWait == null ? Duration.ofMinutes(15) : runWait;
    pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
    maxArtifactBytes = maxArtifactBytes <= 0 ? 16L * 1024 * 1024 : maxArtifactBytes;
  }
}
