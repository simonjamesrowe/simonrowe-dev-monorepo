package com.simonrowe.factory.ticketagent.domain;

/**
 * The agent's pull request.
 *
 * @param number the pull request number
 * @param url its web address
 * @param nodeId the GraphQL id, which marking it a draft takes
 */
public record PullRequestRef(int number, String url, String nodeId) {
}
