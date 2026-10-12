package com.simonrowe.playlist;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies for {@link PlaylistController}. */
public final class PlaylistRequests {

  /** The most items one "make audio" request may name. Each one is a text-to-speech render. */
  public static final int MAX_AUDIO_ITEMS = 20;

  private PlaylistRequests() {
  }

  /**
   * Where the listener has got to.
   *
   * @param positionSeconds seconds into the audio; a day is far longer than any narration
   */
  public record Progress(@PositiveOrZero @Max(86_400) double positionSeconds) {
  }

  /**
   * Items to make audio for.
   *
   * @param items the items, at most {@link #MAX_AUDIO_ITEMS}
   */
  public record Audio(
      @NotEmpty @Size(max = MAX_AUDIO_ITEMS) List<@Valid PlaylistItemKey> items
  ) {
  }

  /**
   * What happened to each item.
   *
   * @param results one per distinct item asked for, in the order asked
   */
  public record AudioResults(List<AudioResult> results) {
  }

  /**
   * What happened to one item.
   *
   * @param contentType what kind of content
   * @param contentId its id
   * @param outcome what happened
   */
  public record AudioResult(
      PlaylistContentType contentType,
      String contentId,
      PlaylistAudioOutcome outcome
  ) {
  }

  /**
   * The playlist's setting.
   *
   * @param autoVoice make audio for new summaries without being asked
   */
  public record Settings(boolean autoVoice) {
  }
}
