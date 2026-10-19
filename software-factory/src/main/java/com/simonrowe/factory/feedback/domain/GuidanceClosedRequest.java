package com.simonrowe.factory.feedback.domain;

/**
 * A guidance pull request has closed, merged or not.
 *
 * @param owner the repository owner
 * @param repository the repository name
 * @param pullNumber the pull request number
 * @param pullRequestUrl its {@code html_url}, which is the URL attached to its Linear ticket
 * @param merged whether it closed by merging
 */
public record GuidanceClosedRequest(
    String owner, String repository, int pullNumber, String pullRequestUrl, boolean merged) {
}
