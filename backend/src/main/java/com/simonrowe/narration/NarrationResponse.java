package com.simonrowe.narration;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NarrationResponse(
    PublicState state,
    long version,
    String audioUrl,
    Long durationSeconds,
    boolean retryable,
    String message
) {

  public enum PublicState {
    NOT_REQUESTED,
    QUEUED,
    PROCESSING,
    READY,
    FAILED,
    UNAVAILABLE,
    INELIGIBLE
  }

  public static NarrationResponse notRequested() {
    return new NarrationResponse(PublicState.NOT_REQUESTED, 0, null, null,
        false, "Listen to this post");
  }

  public static NarrationResponse unavailable() {
    return new NarrationResponse(PublicState.UNAVAILABLE, 0, null, null,
        false, "Narration is temporarily unavailable");
  }

  public static NarrationResponse from(final Narration narration) {
    return switch (narration.status()) {
      case QUEUED -> pending(PublicState.QUEUED, narration);
      case PROCESSING -> pending(PublicState.PROCESSING, narration);
      case READY -> new NarrationResponse(
          PublicState.READY,
          narration.version(),
          narration.audioPath(),
          narration.durationSeconds(),
          false,
          "Generated narration is ready");
      case FAILED -> new NarrationResponse(
          "BUDGET_EXHAUSTED".equals(narration.failureCode())
              ? PublicState.UNAVAILABLE : PublicState.FAILED,
          narration.version(),
          null,
          null,
          narration.retryable(),
          "Audio could not be prepared");
      case UNCERTAIN -> new NarrationResponse(
          PublicState.FAILED,
          narration.version(),
          null,
          null,
          false,
          "Audio could not be prepared safely");
      case STALE -> notRequested();
    };
  }

  public boolean isTerminal() {
    return switch (state) {
      case QUEUED, PROCESSING -> false;
      default -> true;
    };
  }

  private static NarrationResponse pending(
      final PublicState state,
      final Narration narration
  ) {
    return new NarrationResponse(state, narration.version(), null, null,
        false, "Preparing audio. You can keep reading");
  }
}
