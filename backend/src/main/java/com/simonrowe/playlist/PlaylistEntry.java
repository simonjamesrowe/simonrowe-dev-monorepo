package com.simonrowe.playlist;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonrowe.narration.NarrationResponse;
import com.simonrowe.summary.ArticleSummaryResponse;
import java.time.Instant;

/**
 * One playlist item as the page shows it: the item's own state joined with the content's
 * title and image, its summary and its audio.
 *
 * <p>Queued entries arrive in play order: anything chosen to "play next", latest choice first,
 * then by the content's own date, newest first.
 *
 * <p>The page sorts these into three groups. A queued item with {@code READY} audio is up next.
 * A queued item without it is waiting for audio. A listened item has been heard.
 *
 * @param contentType what kind of content
 * @param contentId the aggregated article id or the blog id
 * @param status queued or listened
 * @param progressSeconds where the last listener stopped
 * @param addedVia what put it here
 * @param addedAt when it joined
 * @param listenedAt when it was last played to the end, or null
 * @param voiceRequested someone asked for its audio
 * @param pinnedAt when someone chose "play next", or null
 * @param title the article's or post's title
 * @param href the original article, or the post's page on this site
 * @param external whether {@code href} leaves the site
 * @param sourceName the publisher, or {@code Simon Rowe} for a post
 * @param imageUrl the card image, or null
 * @param publishedAt the content's own date
 * @param favourite whether the article is hearted; null for a post, which cannot be hearted
 * @param summaryState the summary's state; null for a post, which is read in full
 * @param summaryRetryable whether a failed summary could succeed on another try
 * @param audio the audio's state, with a URL once it is ready
 * @param estimatedSeconds a guess at the audio's length before it exists
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaylistEntry(
    PlaylistContentType contentType,
    String contentId,
    PlaylistItemStatus status,
    double progressSeconds,
    PlaylistSource addedVia,
    Instant addedAt,
    Instant listenedAt,
    boolean voiceRequested,
    Instant pinnedAt,
    String title,
    String href,
    boolean external,
    String sourceName,
    String imageUrl,
    Instant publishedAt,
    Boolean favourite,
    ArticleSummaryResponse.PublicState summaryState,
    Boolean summaryRetryable,
    Audio audio,
    Long estimatedSeconds
) {

  /**
   * The item's audio.
   *
   * @param state the narration's public state
   * @param audioUrl site-relative path under {@code /uploads/}, only when ready
   * @param durationSeconds audio length, only when ready
   * @param timingsUrl the read-along timings, only when ready and the audio has them
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Audio(
      NarrationResponse.PublicState state,
      String audioUrl,
      Long durationSeconds,
      String timingsUrl
  ) {
  }
}
