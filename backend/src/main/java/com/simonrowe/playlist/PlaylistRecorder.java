package com.simonrowe.playlist;

/**
 * How the rest of the site puts things on the playlist: summarising an article, hearting it,
 * or pressing Listen.
 *
 * <p>An interface so the controllers that call it need only this one method, and their tests
 * can pass a lambda.
 */
@FunctionalInterface
public interface PlaylistRecorder {

  /**
   * Adds the content to the playlist if it is not on it already. Something already queued or
   * already heard is left alone.
   *
   * <p><strong>Never throws.</strong> The caller's own action (the heart, the summary, the
   * audio) must succeed whether or not the playlist could be updated.
   *
   * @param contentType what kind of content
   * @param contentId its id
   * @param source what the caller was doing
   */
  void record(PlaylistContentType contentType, String contentId, PlaylistSource source);
}
