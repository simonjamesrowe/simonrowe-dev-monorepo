package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class GuardrailVerdictRegistryTest {

  private final GuardrailVerdictRegistry registry = new GuardrailVerdictRegistry();

  @Test
  void takeVerdictReturnsRecordedVerdict() {
    registry.record("session-1", "SAFE");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("SAFE");
  }

  @Test
  void takeVerdictRemovesTheEntrySoItIsReadOnce() {
    registry.record("session-1", "OFF_TOPIC");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("OFF_TOPIC");
    assertThat(registry.takeVerdict("session-1")).isNull();
  }

  @Test
  void takeVerdictReturnsNullForUnknownSession() {
    assertThat(registry.takeVerdict("never-seen")).isNull();
  }

  @Test
  void recordOverwritesThePreviousVerdictForTheSameSession() {
    registry.record("session-1", "SAFE");
    registry.record("session-1", "HARMFUL");

    assertThat(registry.takeVerdict("session-1")).isEqualTo("HARMFUL");
  }

  @Test
  void recordIgnoresNullSessionIdAndNullVerdict() {
    registry.record(null, "SAFE");
    registry.record("session-2", null);

    assertThat(registry.takeVerdict("session-2")).isNull();
  }

  @Test
  void clearSessionRemovesTheEntry() {
    registry.record("session-1", "SAFE");

    registry.clearSession("session-1");

    assertThat(registry.takeVerdict("session-1")).isNull();
  }

  @Test
  void clearSessionIgnoresNullSessionId() {
    assertThatCode(() -> registry.clearSession(null)).doesNotThrowAnyException();
  }
}
