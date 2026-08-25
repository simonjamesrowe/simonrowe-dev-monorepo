package com.simonrowe.summary;

/**
 * The reasons a summary can fail, and whether retrying is worth anything.
 *
 * <p>Stored on the document rather than only returned, so a repeat request on a
 * non-retryable failure does not silently re-spend on the model.
 */
public final class ArticleSummaryFailure {

  /**
   * The best available source text is under
   * {@code ArticleSourceTextProvider.HARD_MIN_SOURCE_CHARS}. Not retryable: the model is
   * never asked to invent five paragraphs from a feed snippet, and a retry would find the
   * same thin source.
   */
  public static final String INSUFFICIENT_SOURCE_TEXT = "INSUFFICIENT_SOURCE_TEXT";

  /** The model call threw, or returned nothing usable. Retryable. */
  public static final String MODEL_ERROR = "MODEL_ERROR";

  /** The article is gone or no longer visible. Not retryable. */
  public static final String ARTICLE_NOT_FOUND = "ARTICLE_NOT_FOUND";

  private ArticleSummaryFailure() {
  }
}
