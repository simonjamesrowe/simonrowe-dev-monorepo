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
 * @param hasNonBotReply whether anyone other than a bot has replied. It is what separates a decline
 *     (reply with the reason, then resolve) from a fix being cleared (push, then resolve). Nothing
 *     is deleted any more, so a human reply itself is safe by construction
 * @param resolvedBy the login of the person who resolved the thread, or {@code null} when it is
 *     open or was resolved by a bot. GitHub's {@code resolvedBy} is a {@code User}, so this
 *     reviewer's own resolutions read as null; a {@code [bot]} login is cleared as well
 * @param raisedAt the commit the root comment was posted against ({@code originalCommit}), or
 *     {@code null} when GitHub did not say. Whether the finding's file changed after it is what
 *     separates "fixed" from "the model did not mention it this time"
 * @param finding the finding as this reviewer rendered it in the root comment, or {@code null}
 *     when the thread is not this reviewer's or its body could not be read back
 */
public record ExistingThread(
    String nodeId,
    String fingerprint,
    boolean legacyMarker,
    boolean resolved,
    boolean hasNonBotReply,
    String resolvedBy,
    String raisedAt,
    ReviewFinding finding) {

  public ExistingThread {
    if (resolvedBy != null && (resolvedBy.isBlank() || resolvedBy.endsWith("[bot]"))) {
      resolvedBy = null;
    }
  }

  /** A thread with no resolver, origin commit or parsed finding, as tests of the table need. */
  public ExistingThread(
      final String nodeId,
      final String fingerprint,
      final boolean legacyMarker,
      final boolean resolved,
      final boolean hasNonBotReply) {
    this(nodeId, fingerprint, legacyMarker, resolved, hasNonBotReply, null, null, null);
  }

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

  /** Whether a person, rather than this reviewer, resolved the thread. */
  public boolean resolvedByPerson() {
    return resolved && resolvedBy != null;
  }
}
