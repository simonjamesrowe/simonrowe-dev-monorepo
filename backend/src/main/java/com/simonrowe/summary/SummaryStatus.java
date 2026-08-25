package com.simonrowe.summary;

/**
 * Persisted lifecycle of an {@link ArticleSummary}.
 *
 * <p>The wire response carries one further state that is never stored —
 * {@code NOT_REQUESTED}, meaning no document exists for the article. See
 * {@link ArticleSummaryResponse.PublicState}.
 */
public enum SummaryStatus {
  GENERATING,
  READY,
  FAILED
}
