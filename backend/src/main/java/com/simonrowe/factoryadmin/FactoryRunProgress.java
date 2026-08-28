package com.simonrowe.factoryadmin;

/**
 * A factory run's normalised state as the browser sees it.
 *
 * <p>Identical in shape to the factory's own record: the backend's job on this path is
 * authorisation and error normalisation, not translation.
 *
 * @param workflowId the durable workflow identity
 * @param runId the specific run, null when Temporal could not be asked
 * @param executionStatus Temporal's execution status name
 * @param phase the workflow's self-reported phase, null when it could not be queried
 * @param detail the workflow's self-reported detail, on the same terms
 * @param terminal whether no further change is expected, so the console can stop polling
 */
public record FactoryRunProgress(
    String workflowId,
    String runId,
    String executionStatus,
    String phase,
    String detail,
    boolean terminal) {
}
