package com.simonrowe.factory.deploy.api;

/**
 * The webhook's response when a deploy has been requested.
 *
 * @param workflowId always {@code deploy-prod}
 * @param runId the Temporal run the signal reached, so an operator can find it in the UI
 * @param sha the commit that will be deployed
 */
public record DeployAccepted(String workflowId, String runId, String sha) {
}
