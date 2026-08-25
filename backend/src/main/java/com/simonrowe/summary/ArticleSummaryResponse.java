package com.simonrowe.summary;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Wire shape for an article summary.
 *
 * <p>Modelled on {@code NarrationResponse}, including its habit of carrying one public
 * state that is never persisted. There is deliberately <b>no</b> {@code UNAVAILABLE}
 * state: narration has one because the text-to-speech provider can be unconfigured,
 * whereas the chat model is a hard dependency of the running application — if it is
 * unreachable that is a {@code MODEL_ERROR} on a specific attempt, not a capability the
 * deployment lacks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArticleSummaryResponse(
    PublicState state,
    long version,
    String body,
    String model,
    Instant completedAt,
    String failureCode,
    boolean retryable,
    String message
) {

  static final String NOT_REQUESTED_MESSAGE = "Summarise this article";
  static final String GENERATING_MESSAGE =
      "Writing the summary. This usually takes under a minute";
  static final String READY_MESSAGE = "Summary ready";
  static final String INSUFFICIENT_SOURCE_MESSAGE =
      "There is not enough of this article available to summarise. "
          + "Read the original instead.";
  static final String ARTICLE_NOT_FOUND_MESSAGE =
      "This article is no longer available.";
  static final String MODEL_ERROR_MESSAGE =
      "The summary could not be written. Please try again.";

  public enum PublicState {
    NOT_REQUESTED,
    GENERATING,
    READY,
    FAILED
  }

  public static ArticleSummaryResponse notRequested() {
    return new ArticleSummaryResponse(PublicState.NOT_REQUESTED, 0, null, null,
        null, null, false, NOT_REQUESTED_MESSAGE);
  }

  public static ArticleSummaryResponse from(final ArticleSummary summary) {
    return switch (summary.status()) {
      case GENERATING -> new ArticleSummaryResponse(
          PublicState.GENERATING,
          summary.version(),
          null,
          null,
          null,
          null,
          false,
          GENERATING_MESSAGE);
      case READY -> new ArticleSummaryResponse(
          PublicState.READY,
          summary.version(),
          summary.body(),
          summary.model(),
          summary.completedAt(),
          null,
          false,
          READY_MESSAGE);
      case FAILED -> new ArticleSummaryResponse(
          PublicState.FAILED,
          summary.version(),
          null,
          null,
          null,
          summary.failureCode(),
          summary.retryable(),
          messageFor(summary.failureCode()));
    };
  }

  /** Only {@code GENERATING} can still change without a new request. */
  public boolean isTerminal() {
    return state != PublicState.GENERATING;
  }

  private static String messageFor(final String failureCode) {
    return switch (failureCode == null ? "" : failureCode) {
      case ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT -> INSUFFICIENT_SOURCE_MESSAGE;
      case ArticleSummaryFailure.ARTICLE_NOT_FOUND -> ARTICLE_NOT_FOUND_MESSAGE;
      default -> MODEL_ERROR_MESSAGE;
    };
  }
}
