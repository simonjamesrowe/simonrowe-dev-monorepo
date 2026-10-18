package com.simonrowe.playlist;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The playlist's one setting. A single document with a fixed id, because the playlist is
 * shared rather than per person.
 *
 * @param id always {@link #SINGLETON_ID}
 * @param autoVoice make audio for every newly summarised item without being asked
 */
@Document(collection = PlaylistSettings.COLLECTION)
public record PlaylistSettings(@Id String id, boolean autoVoice) {

  public static final String COLLECTION = "playlist_settings";
  public static final String SINGLETON_ID = "playlist";

  public static PlaylistSettings defaults() {
    return new PlaylistSettings(SINGLETON_ID, false);
  }
}
