package com.simonrowe.factory.ticketagent.domain;

import java.time.Duration;

/**
 * The configuration a run needs, read once through an activity.
 *
 * <p>A {@code @WorkflowImpl} cannot inject Spring properties, and reading them directly would make
 * the workflow non-deterministic across a restart with different values. An activity's result is
 * recorded in history, so the run keeps the settings it started with.
 *
 * @param label the hand-over label
 * @param branchPrefix every agent branch starts with this
 * @param maxAttempts agent runs per ticket
 * @param checksWait how long one attempt waits for CI
 * @param reviewWait how long to wait for the reviewer once CI is done
 * @param mergeWait how long a green pull request is watched for its merge
 */
public record TicketAgentSettings(
    String label,
    String branchPrefix,
    int maxAttempts,
    Duration checksWait,
    Duration reviewWait,
    Duration mergeWait) {
}
