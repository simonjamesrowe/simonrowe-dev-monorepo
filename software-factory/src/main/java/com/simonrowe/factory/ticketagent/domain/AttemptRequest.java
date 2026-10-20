package com.simonrowe.factory.ticketagent.domain;

import com.simonrowe.factory.linear.domain.LinearTicket;

/**
 * One agent run.
 *
 * @param ticket the ticket, as read when the run began
 * @param branch the agent's branch
 * @param attempt 1 for the first attempt, 2 and up for repairs
 * @param failure what CI or the reviewer said about the previous attempt; null on the first
 * @param dryRun push nothing; return the diff instead
 */
public record AttemptRequest(
    LinearTicket ticket, String branch, int attempt, String failure, boolean dryRun) {
}
