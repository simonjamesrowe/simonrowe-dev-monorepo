package com.simonrowe.platform;

/**
 * How a release record came to exist.
 *
 * <p>The distinction is what lets the page be honest: {@code RUNNING} is evidenced by an
 * artifact reporting its own SHA, whereas {@code PUBLISHED_HISTORY} is derived from
 * {@code main}'s commit history and only evidences that an image was published.
 */
public enum ReleaseSource {

  /** A backend instance booted reporting this SHA, so it demonstrably ran. */
  RUNNING,

  /** Derived from baked git history: published to ghcr, deployment unknown. */
  PUBLISHED_HISTORY
}
