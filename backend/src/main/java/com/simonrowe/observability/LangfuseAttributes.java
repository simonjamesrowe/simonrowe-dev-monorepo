package com.simonrowe.observability;

/**
 * Span attribute names that Langfuse recognises, taken verbatim from
 * {@code packages/shared/src/server/otel/attributes.ts} at tag v3.212.0.
 *
 * <p>These are load-bearing strings: Langfuse silently ignores anything it does not
 * recognise, so a typo produces an empty trace field rather than an error.
 */
public final class LangfuseAttributes {

  /** Groups traces into a Langfuse Session. */
  public static final String SESSION_ID = "session.id";

  /** Trace display name; without it Langfuse falls back to the root span name. */
  public static final String TRACE_NAME = "langfuse.trace.name";

  /** Trace-level input. Applied by Langfuse's hasTraceUpdates() even from a non-root span. */
  public static final String TRACE_INPUT = "langfuse.trace.input";

  /** Trace-level output. */
  public static final String TRACE_OUTPUT = "langfuse.trace.output";

  /** Highest-precedence observation input mapping. */
  public static final String OBSERVATION_INPUT = "langfuse.observation.input";

  /** Highest-precedence observation output mapping. */
  public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";

  /** Separates development traffic from production within one project. */
  public static final String ENVIRONMENT = "langfuse.environment";

  /**
   * Cap on any single content attribute. The chat system prompt alone is roughly 6 KB before
   * retrieval context is appended, and a tool-using turn produces several generations, so
   * uncapped capture risks the 4 MB gRPC message limit and bloats ClickHouse on the Pi.
   */
  public static final int MAX_ATTRIBUTE_CHARS = 32_768;

  private static final String TRUNCATION_MARKER = "…[truncated]";

  private LangfuseAttributes() {
  }

  /**
   * Caps a content value at {@link #MAX_ATTRIBUTE_CHARS}, appending a visible marker so a
   * truncated value is never mistaken for a complete one.
   *
   * @param value the content, may be null
   * @return null if the input was null, otherwise the capped value
   */
  public static String truncate(final String value) {
    if (value == null || value.length() <= MAX_ATTRIBUTE_CHARS) {
      return value;
    }
    return value.substring(0, MAX_ATTRIBUTE_CHARS) + TRUNCATION_MARKER;
  }
}
