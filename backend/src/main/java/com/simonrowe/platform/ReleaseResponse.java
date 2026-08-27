package com.simonrowe.platform;

import java.time.Instant;

/**
 * One changelog entry as served.
 *
 * <p>{@code summary} is null while {@code summaryStatus} is not {@code READY}. The entry is
 * still returned: the page renders the commit subject with a pending note rather than hiding
 * a release because its paragraph has not been written yet.
 *
 * @param sha the full commit SHA
 * @param shortSha the seven-character SHA, as rendered and linked
 * @param type the conventional-commit type, or {@code other}
 * @param subject the commit subject line
 * @param commitTime when the commit was authored
 * @param running true when the backend serving this response was built from this commit
 * @param summary the AI-written release note, or null
 * @param summaryStatus where that summary has got to
 */
public record ReleaseResponse(
    String sha,
    String shortSha,
    String type,
    String subject,
    Instant commitTime,
    boolean running,
    String summary,
    ReleaseSummaryStatus summaryStatus) {

  /**
   * Maps a stored release for the wire.
   *
   * @param release the stored release
   * @param runningSha the SHA this backend was built from
   * @return the response entry
   */
  static ReleaseResponse from(final PlatformRelease release, final String runningSha) {
    return new ReleaseResponse(
        release.getId(),
        release.getShortSha(),
        release.getType(),
        release.getSubject(),
        release.getCommitTime(),
        release.getId().equals(runningSha),
        release.getSummaryStatus() == ReleaseSummaryStatus.READY ? release.getSummary() : null,
        release.getSummaryStatus());
  }
}
