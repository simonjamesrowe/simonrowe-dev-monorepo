package com.simonrowe.factory.deploy.domain;

import java.util.List;

/**
 * Everything one deploy needs.
 *
 * <p>Passed as a workflow argument rather than injected: a {@code @WorkflowImpl} is instantiated
 * by the Temporal SDK and not by Spring, so the workflow cannot hold a properties bean.
 *
 * @param sha the commit to deploy — an exact sha, never a moving tag
 * @param trigger {@code workflow_run} or {@code manual}
 * @param installationId null on purpose when unknown, so the activity resolves it at run time; a
 *     configured-but-empty value would make the token lookup fall back to a static
 *     {@code GITHUB_TOKEN} this service does not set, giving an anonymous call and a 403
 * @param syncConfig whether to fast-forward the host checkout
 * @param rollbackEnabled whether a failed verification may roll back
 * @param services the services whose images to pull and recreate
 * @param dryRun run the phases without deploying (the script's own DRY_RUN)
 */
public record DeployRequest(
    String sha,
    String trigger,
    Long installationId,
    boolean syncConfig,
    boolean rollbackEnabled,
    List<String> services,
    boolean dryRun) {

  /** Trigger value for a deploy started by a GitHub {@code workflow_run} delivery. */
  public static final String TRIGGER_WEBHOOK = "workflow_run";

  /** Trigger value for a deploy started by hand from the Temporal UI. */
  public static final String TRIGGER_MANUAL = "manual";

  public DeployRequest {
    trigger = trigger == null || trigger.isBlank() ? TRIGGER_MANUAL : trigger;
    services = services == null ? List.of() : List.copyOf(services);
  }
}
