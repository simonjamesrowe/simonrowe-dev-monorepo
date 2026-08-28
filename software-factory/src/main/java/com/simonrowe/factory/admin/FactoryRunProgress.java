package com.simonrowe.factory.admin;

/**
 * One factory run's state, normalised across every module.
 *
 * <p>Two independent facts are reported rather than one. {@code executionStatus} is Temporal's own
 * view and is the only thing that can say a run has stopped; {@code phase} is the workflow's own
 * query and is the only thing that can say where it got to. A run that died before answering a
 * query has a status and no phase, which is exactly the case a single combined field would hide.
 *
 * @param workflowId the durable workflow identity the console polls
 * @param runId the specific run, null when Temporal could not be asked
 * @param executionStatus Temporal's execution status name, or {@code UNKNOWN}
 * @param phase the workflow's self-reported phase, null when it could not be queried
 * @param detail the workflow's self-reported detail, null on the same terms
 * @param terminal whether no further change is expected
 */
public record FactoryRunProgress(
    String workflowId,
    String runId,
    String executionStatus,
    String phase,
    String detail,
    boolean terminal) {
}
