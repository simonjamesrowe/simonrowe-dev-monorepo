package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class ToolCallCounterTest {

  private final ToolCallCounter counter = new ToolCallCounter();

  @Test
  void takeCountReturnsTheIncrementedCount() {
    counter.increment("session-1", 2);

    assertThat(counter.takeCount("session-1")).isEqualTo(2);
  }

  @Test
  void takeCountRemovesTheEntrySoItIsReadOnce() {
    counter.increment("session-1", 2);

    assertThat(counter.takeCount("session-1")).isEqualTo(2);
    assertThat(counter.takeCount("session-1")).isZero();
  }

  @Test
  void takeCountReturnsZeroForUnknownSession() {
    assertThat(counter.takeCount("never-seen")).isZero();
  }

  @Test
  void repeatedIncrementsAccumulate() {
    counter.increment("session-1", 2);
    counter.increment("session-1", 1);

    assertThat(counter.takeCount("session-1")).isEqualTo(3);
  }

  @Test
  void clearSessionRemovesTheEntry() {
    counter.increment("session-1", 2);

    counter.clearSession("session-1");

    assertThat(counter.takeCount("session-1")).isZero();
  }

  @Test
  void incrementIgnoresNullSessionId() {
    assertThatCode(() -> counter.increment(null, 1)).doesNotThrowAnyException();
  }

  @Test
  void takeCountIgnoresNullSessionId() {
    assertThatCode(() -> counter.takeCount(null)).doesNotThrowAnyException();
    assertThat(counter.takeCount(null)).isZero();
  }

  @Test
  void clearSessionIgnoresNullSessionId() {
    assertThatCode(() -> counter.clearSession(null)).doesNotThrowAnyException();
  }
}
