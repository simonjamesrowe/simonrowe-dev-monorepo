package com.simonrowe.factory.ticketagent.domain;

/**
 * What a ticket run did.
 *
 * @param identifier the Linear identifier
 * @param outcome how it ended
 * @param attempts how many agent runs it used
 * @param pullRequestUrl the pull request, or null when none was opened
 * @param detail one line saying why it ended where it did
 */
public record TicketAgentResult(
    String identifier,
    TicketAgentOutcome outcome,
    int attempts,
    String pullRequestUrl,
    String detail) {
}
