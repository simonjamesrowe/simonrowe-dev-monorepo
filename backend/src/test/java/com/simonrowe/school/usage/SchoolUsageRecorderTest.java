package com.simonrowe.school.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchoolUsageRecorderTest {

  private static final double TOLERANCE = 1e-9;

  private SchoolUsageRepository repository;
  private SchoolUsageRecorder recorder;

  @BeforeEach
  void setUp() {
    repository = mock(SchoolUsageRepository.class);
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    recorder = new SchoolUsageRecorder(repository, "salt");
  }

  @Test
  @DisplayName("prices input and output separately")
  void pricesInputAndOutput() {
    // gpt-5.6-luna: $0.20 in, $1.20 out per million.
    assertThat(recorder.cost("gpt-5.6-luna", 1_000_000, 1_000_000, 0))
        .isCloseTo(1.40, org.assertj.core.data.Offset.offset(TOLERANCE));
  }

  @Test
  @DisplayName("cached tokens are billed at the cached rate, not on top of the full rate")
  void cachedTokensAreDiscountedNotAdded() {
    // The whole prompt cached: 1M at $0.02 rather than 1M at $0.20. Adding the cached cost on
    // top of the full input cost would overstate every cached turn — and with a large static
    // system prompt, that is nearly every turn after the first.
    assertThat(recorder.cost("gpt-5.6-luna", 1_000_000, 0, 1_000_000))
        .isCloseTo(0.02, org.assertj.core.data.Offset.offset(TOLERANCE));
  }

  @Test
  @DisplayName("an unknown model costs zero rather than guessing")
  void unknownModelIsZero() {
    // A confident wrong number is worse than a visible gap.
    assertThat(recorder.cost("some-future-model", 1_000_000, 1_000_000, 0)).isZero();
    assertThat(recorder.cost(null, 1000, 1000, 0)).isZero();
  }

  @Test
  @DisplayName("an estimated call is flagged as estimated")
  void estimatesAreFlagged() {
    recorder.recordEstimated(SchoolUsage.Kind.EMBEDDING, "text-embedding-3-small", 4000, 0);

    final ArgumentCaptor<SchoolUsage> captor = ArgumentCaptor.forClass(SchoolUsage.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().estimated()).isTrue();
    assertThat(captor.getValue().inputTokens()).isEqualTo(1000);
  }

  @Test
  @DisplayName("an image token estimate is stored as tokens and remains marked estimated")
  void tokenEstimatesAreNotDividedAsCharacters() {
    recorder.recordEstimatedTokens(SchoolUsage.Kind.TRANSCRIBE, "gpt-5.6-luna", 2500, 100);

    final ArgumentCaptor<SchoolUsage> captor = ArgumentCaptor.forClass(SchoolUsage.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().estimated()).isTrue();
    assertThat(captor.getValue().inputTokens()).isEqualTo(2500);
    assertThat(captor.getValue().outputTokens()).isEqualTo(100);
  }

  @Test
  @DisplayName("the client address is hashed, never stored")
  void clientAddressIsHashed() {
    recorder.record(SchoolUsage.Kind.CHAT, "gpt-5-nano", 10, 5, 0, "session-1", "203.0.113.7");

    final ArgumentCaptor<SchoolUsage> captor = ArgumentCaptor.forClass(SchoolUsage.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().clientHash())
        .isNotNull()
        .doesNotContain("203.0.113.7");
    assertThat(captor.getValue().sessionId()).isEqualTo("session-1");
  }

  @Test
  @DisplayName("a failure to record never propagates to the caller")
  void recordingFailuresAreSwallowed() {
    // The bookkeeping must never fail the request that incurred the cost.
    when(repository.save(any())).thenThrow(new RuntimeException("mongo down"));

    recorder.record(SchoolUsage.Kind.CHAT, "gpt-5-nano", 10, 5, 0, "s", null);
  }
}
