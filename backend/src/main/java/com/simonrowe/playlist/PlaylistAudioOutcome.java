package com.simonrowe.playlist;

/** What happened to one item when audio was asked for. */
public enum PlaylistAudioOutcome {
  /** Audio already exists. */
  READY,
  /** Audio is queued with the text-to-speech provider. */
  QUEUED,
  /** Audio is being made. */
  PROCESSING,
  /** The summary is still being written; audio follows when it is. */
  SUMMARISING,
  /** Making the audio failed. */
  FAILED,
  /** Text-to-speech is switched off, or this month's budget is spent. */
  UNAVAILABLE,
  /** This item can never have audio: its article is gone, or there is too little of it. */
  INELIGIBLE,
  /** The item is not queued on the playlist. */
  NOT_IN_PLAYLIST
}
