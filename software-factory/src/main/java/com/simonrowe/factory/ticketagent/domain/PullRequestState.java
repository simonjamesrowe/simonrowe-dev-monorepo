package com.simonrowe.factory.ticketagent.domain;

/**
 * The agent's pull request as GitHub reports it now.
 *
 * @param open whether it is still open
 * @param merged whether it merged
 * @param autoMergeArmed whether GitHub will merge it once its checks allow
 */
public record PullRequestState(boolean open, boolean merged, boolean autoMergeArmed) {
}
