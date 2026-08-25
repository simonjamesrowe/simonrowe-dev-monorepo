package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationBudgetServiceTest {

  private final NarrationRepository repository = mock(NarrationRepository.class);

  @Test
  void permitsExactLimitAndRejectsOneCharacterOver() {
    Narration used = narration("used", 60);
    used.markProviderRequestStarted(Instant.parse("2026-08-01T00:00:00Z"));
    when(repository.findByProviderRequestStartedTrueAndRequestedAtBetween(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(used));
    NarrationBudgetService budget = new NarrationBudgetService(repository, properties(100));

    assertThat(budget.allows(narration("exact", 40),
        Instant.parse("2026-08-11T12:00:00Z"))).isTrue();
    assertThat(budget.allows(narration("over", 41),
        Instant.parse("2026-08-11T12:00:00Z"))).isFalse();
  }

  @Test
  void permitsResumeWithoutChargingAgainAndBlocksZeroBudget() {
    Narration resumed = narration("resume", 500);
    resumed.markProviderRequestStarted(Instant.now());

    assertThat(new NarrationBudgetService(repository, properties(0))
        .allows(resumed, Instant.now())).isTrue();
    assertThat(new NarrationBudgetService(repository, properties(0))
        .allows(narration("new", 1), Instant.now())).isFalse();
  }

  private static Narration narration(final String id, final int characters) {
    return new Narration(id, NarrationContentType.BLOG, "blog", characters, "voice", "en-GB", "MP3",
        "narrations/" + id + ".mp3", Instant.now());
  }

  static NarrationProperties properties(final long limit) {
    return new NarrationProperties(true, "project", "123456789012", null, "global",
        "voice", "en-GB",
        "bucket", 50_000, limit, Duration.ofMillis(1), Duration.ofSeconds(1),
        Duration.ofSeconds(1), Duration.ofSeconds(1));
  }
}
