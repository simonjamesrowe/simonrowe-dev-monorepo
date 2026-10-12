package com.simonrowe.playlist;

/** Where an item sits in the playlist. */
public enum PlaylistItemStatus {
  /** Waiting to be heard, whether or not it has audio yet. */
  QUEUED,
  /** Played to the end. Kept for 30 days, then removed by a TTL index. */
  LISTENED
}
