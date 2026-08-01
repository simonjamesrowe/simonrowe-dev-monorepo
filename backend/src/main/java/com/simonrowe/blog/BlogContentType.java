package com.simonrowe.blog;

/**
 * Distinguishes hand-written engineering posts from generated weekly digests.
 *
 * <p>Stored explicitly on the blog document rather than derived from the presence of a
 * "Weekly Digest" tag: deriving it would mean loading tags on every list query, would
 * leave the classification implicit, and would give an author no way to override it.
 * The tag is retained for display.
 */
public enum BlogContentType {

  /** A hand-written post. The default for anything created by an author. */
  ENGINEERING,

  /** A post produced by the weekly digest agent. */
  DIGEST;

  /** The classification applied to a post that has none stored. */
  public static final BlogContentType DEFAULT = ENGINEERING;

  /**
   * Resolves a possibly-absent stored value to a definite one.
   *
   * @param contentType the stored value, which may be {@code null} for documents written
   *     before the backfill migration ran
   * @return {@code contentType}, or {@link #DEFAULT} when it is {@code null}
   */
  public static BlogContentType orDefault(final BlogContentType contentType) {
    return contentType == null ? DEFAULT : contentType;
  }
}
