package com.simonrowe.playlist;

/** What put an item on the playlist. */
public enum PlaylistSource {
  /** Somebody asked for the article's summary. */
  SUMMARISED,
  /** Somebody hearted the article, which also asks for its summary. */
  FAVOURITED,
  /** Somebody pressed Listen on it. */
  LISTENED,
  /** Added from the playlist itself, including "play again" on something already heard. */
  ADDED,
  /** Present before the playlist existed: summaries from its first two weeks, added by V056. */
  BACKFILLED
}
