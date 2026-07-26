package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LangfuseAttributesTest {

  @Test
  void truncateReturnsNullForNull() {
    assertThat(LangfuseAttributes.truncate(null)).isNull();
  }

  @Test
  void truncateLeavesShortValueUnchanged() {
    assertThat(LangfuseAttributes.truncate("hello")).isEqualTo("hello");
  }

  @Test
  void truncateLeavesValueAtExactCapUnchanged() {
    final String atCap = "x".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS);

    assertThat(LangfuseAttributes.truncate(atCap)).isEqualTo(atCap);
  }

  @Test
  void truncateAppendsMarkerWhenOverCap() {
    final String overCap = "x".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + 1);

    final String result = LangfuseAttributes.truncate(overCap);

    assertThat(result).hasSize(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + "…[truncated]".length());
    assertThat(result).endsWith("…[truncated]");
    assertThat(result).startsWith("xxx");
  }

  @Test
  void attributeNamesMatchLangfuseSpelling() {
    assertThat(LangfuseAttributes.SESSION_ID).isEqualTo("session.id");
    assertThat(LangfuseAttributes.TRACE_NAME).isEqualTo("langfuse.trace.name");
    assertThat(LangfuseAttributes.TRACE_INPUT).isEqualTo("langfuse.trace.input");
    assertThat(LangfuseAttributes.TRACE_OUTPUT).isEqualTo("langfuse.trace.output");
    assertThat(LangfuseAttributes.OBSERVATION_INPUT).isEqualTo("langfuse.observation.input");
    assertThat(LangfuseAttributes.OBSERVATION_OUTPUT).isEqualTo("langfuse.observation.output");
    assertThat(LangfuseAttributes.ENVIRONMENT).isEqualTo("langfuse.environment");
  }
}
