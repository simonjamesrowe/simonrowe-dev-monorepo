package com.simonrowe.factory.codereview.domain;

/**
 * One review conversation already on the pull request, as GitHub reports it.
 *
 * <p>Fetched over GraphQL rather than REST because REST cannot see {@code isResolved} at all — a
 * comment listing carries no thread identity and no resolution state, which is why the old
 * delete-everything strategy could not have been anything else.
 *
 * @param nodeId the GraphQL node id, which is the handle {@code resolveReviewThread} takes
 * @param fingerprint the finding identity parsed out of the root comment's marker, or {@code null}
 *     when this reviewer did not open the thread with a fingerprinted marker
 * @param legacyMarker whether the root comment carries the bare pre-identity marker. Separate from
 *     {@code fingerprint} because the two {@code null}-fingerprint cases need opposite treatment: a
 *     legacy thread is this reviewer's to resolve, a human's or a third-party analyser's is not
 * @param resolved GitHub's own {@code isResolved}
 * @param hasNonBotReply whether anyone other than a bot has replied. Carried for logging and for
 *     the runbook rather than for control flow: nothing is deleted any more, so a human reply is
 *     safe by construction rather than by a check
 */
public record ExistingThread(
    String nodeId,
    String fingerprint,
    boolean legacyMarker,
    boolean resolved,
    boolean hasNonBotReply) {

  /**
   * Whether this thread is one this reviewer opened.
   *
   * <p>Threads it did not open — a human's question, a SonarCloud inline comment — are never
   * touched. Required conversation resolution applies to them too, but resolving them is a
   * judgement only a person can make.
   */
  public boolean reviewerOwned() {
    return fingerprint != null || legacyMarker;
  }
}
