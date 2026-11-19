package com.simonrowe.playlist;

/** What put an item on the playlist. */
public enum PlaylistSource {
  /**
   * Somebody asked for the article's summary. That also asks for its audio: summarising and
   * listening are one action on the site, so a summary never waits for a second press to be
   * read aloud.
   */
  SUMMARISED,
  /** Somebody hearted the article, which also asks for its summary. */
  FAVOURITED,
  /** Somebody pressed Listen on it. */
  LISTENED,
  /** Added from the playlist itself, including "play again" on something already heard. */
  ADDED,
  /** Present before the playlist existed: summaries from its first two weeks, added by V056. */
  BACKFILLED;

  /**
   * Whether this action also asks for the item's audio, so it is made as soon as the summary
   * is written ({@link PlaylistService#onSummaryReady}).
   *
   * @return true only for {@link #SUMMARISED}
   */
  public boolean asksForAudio() {
    return this == SUMMARISED;
  }
}
