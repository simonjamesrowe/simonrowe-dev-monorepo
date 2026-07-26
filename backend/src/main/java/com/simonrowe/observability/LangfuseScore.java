package com.simonrowe.observability;

/**
 * One Langfuse score. {@code value} is a number or a string; Langfuse's create-score API has
 * no {@code stringValue} field on the request (that is response-only), so categorical scores
 * carry their label in {@code value}.
 *
 * @param name score name as it appears in the Langfuse UI
 * @param value numeric or string value
 * @param dataType NUMERIC, BOOLEAN or CATEGORICAL
 */
public record LangfuseScore(String name, Object value, String dataType) {

  public static LangfuseScore numeric(final String name, final double value) {
    return new LangfuseScore(name, value, "NUMERIC");
  }

  public static LangfuseScore categorical(final String name, final String value) {
    return new LangfuseScore(name, value, "CATEGORICAL");
  }

  /** Langfuse encodes booleans as 1 or 0 with dataType BOOLEAN. */
  public static LangfuseScore bool(final String name, final boolean value) {
    return new LangfuseScore(name, value ? 1 : 0, "BOOLEAN");
  }
}
