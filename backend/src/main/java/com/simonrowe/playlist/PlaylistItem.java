package com.simonrowe.playlist;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One entry on the site's single, shared playlist.
 *
 * <p>The playlist is global, like favourites: everybody sees the same list, and anybody signed
 * in can change it. It plays in the content's own date order, newest first, except that
 * anything chosen to "play next" comes before everything else.
 *
 * <p>The {@code _id} is {@code <contentType>:<contentId>}, so a piece of content can be on the
 * list at most once without needing an index to say so.
 *
 * <p>Indexes come from {@code V056CreatePlaylist}, because {@code auto-index-creation} is off.
 *
 * @param id {@code <contentType>:<contentId>}, see {@link #idFor}
 * @param contentType what kind of content
 * @param contentId the aggregated article id or the blog id
 * @param pinnedAt when someone chose "play next"; pinned items come first, the latest pin first
 * @param status queued or listened
 * @param progressSeconds how far into the audio the last listener got
 * @param addedVia what put it here
 * @param voiceRequested someone asked for its audio; acted on when its summary is ready
 * @param addedAt when it joined
 * @param updatedAt last change of any kind
 * @param listenedAt when it was last played to the end; the TTL index keys on this
 */
@Document(collection = PlaylistItem.COLLECTION)
public record PlaylistItem(
    @Id String id,
    PlaylistContentType contentType,
    String contentId,
    PlaylistItemStatus status,
    double progressSeconds,
    PlaylistSource addedVia,
    boolean voiceRequested,
    Instant pinnedAt,
    Instant addedAt,
    Instant updatedAt,
    Instant listenedAt
) {

  public static final String COLLECTION = "playlist_items";

  /**
   * The {@code _id} for a piece of content.
   *
   * @param contentType what kind of content
   * @param contentId its id
   * @return {@code <contentType>:<contentId>}
   */
  public static String idFor(final PlaylistContentType contentType, final String contentId) {
    return contentType.name() + ":" + contentId;
  }
}
