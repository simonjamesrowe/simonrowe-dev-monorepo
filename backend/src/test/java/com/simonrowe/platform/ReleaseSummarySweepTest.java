package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReleaseSummarySweepTest extends AbstractIntegrationTest {

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void clearCollection() {
    mongoTemplate.dropCollection(PlatformRelease.class);
  }

  private PlatformRelease pending(final String sha, final long epoch) {
    PlatformRelease release = PlatformRelease.fromBaked(
        new BakedRelease(
            sha, Instant.ofEpochSecond(epoch), "feat: add a thing", "It does a thing.",
            List.of("Thing.java")),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(epoch));
    return repository.insert(release);
  }

  /**
   * Stubs the Embabel inline-LLM chain used by ArticleSectionWriter:
   * {@code ai.withLlm(model).respond(List.of(new UserMessage(prompt))).getContent()}.
   *
   * <p>Confirmed against the {@code embabel-agent-api:1.0.0} jar: {@code Ai.withLlm(String)}
   * returns {@link PromptRunner}, and {@code PromptRunner.respond(List)} returns
   * {@link AssistantMessage}, which has a single-arg {@code String} constructor.
   */
  private ReleaseSummarySweep sweepReturning(final String completion) {
    Ai stubAi = mock(Ai.class);
    PromptRunner runner = mock(PromptRunner.class);
    when(stubAi.withLlm(anyString())).thenReturn(runner);
    when(runner.respond(anyList())).thenReturn(new AssistantMessage(completion));
    return new ReleaseSummarySweep(repository, stubAi, true, 3, 3, "test-model");
  }

  private ReleaseSummarySweep sweepThatFails() {
    Ai stubAi = mock(Ai.class);
    when(stubAi.withLlm(anyString())).thenThrow(new IllegalStateException("model down"));
    return new ReleaseSummarySweep(repository, stubAi, true, 3, 3, "test-model");
  }

  @Test
  void summarisesPendingReleases() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);

    int summarised = sweepReturning("This release added a thing.").sweep();

    assertThat(summarised).isEqualTo(1);
    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
    assertThat(stored.getSummary()).isEqualTo("This release added a thing.");
  }

  @Test
  void capsAnOverlongSummaryBeforeStoringIt() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);

    sweepReturning("x".repeat(5000)).sweep();

    String stored = repository
        .findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow().getSummary();
    // The value is rendered on a public page, so it must not be unbounded no matter what
    // the model returns.
    assertThat(stored).hasSizeLessThan(5000);
    assertThat(stored).endsWith("… (truncated)");
  }

  @Test
  void honoursTheBatchSize() {
    pending("aaa0000abcdef0123456789abcdef0123456789a", 1756200000L);
    pending("bbb0000abcdef0123456789abcdef0123456789a", 1756100000L);
    pending("ccc0000abcdef0123456789abcdef0123456789a", 1756000000L);
    pending("ddd0000abcdef0123456789abcdef0123456789a", 1755900000L);

    assertThat(sweepReturning("A paragraph.").sweep()).isEqualTo(3);
    assertThat(repository.findPending(10)).hasSize(1);
  }

  @Test
  void treatsEmptyCompletionAsFailedAttempt() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);

    sweepReturning("   ").sweep();

    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.PENDING);
    assertThat(stored.getSummaryAttempts()).isEqualTo(1);
  }

  @Test
  void givesUpAfterTheAttemptLimit() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    ReleaseSummarySweep sweep = sweepThatFails();

    sweep.sweep();
    sweep.sweep();
    sweep.sweep();

    PlatformRelease stored =
        repository.findById("840c311abcdef0123456789abcdef0123456789a").orElseThrow();
    assertThat(stored.getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.FAILED);
    assertThat(stored.getSummaryAttempts()).isEqualTo(3);
  }

  @Test
  void doesNothingWhenDisabled() {
    pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    Ai stubAi = mock(Ai.class);
    ReleaseSummarySweep disabled =
        new ReleaseSummarySweep(repository, stubAi, false, 3, 3, "test-model");

    assertThat(disabled.sweep()).isZero();
    verify(stubAi, never()).withLlm(anyString());
  }

  @Test
  void leavesReadyReleasesAlone() {
    PlatformRelease ready = pending("840c311abcdef0123456789abcdef0123456789a", 1756200000L);
    ready.setSummaryStatus(ReleaseSummaryStatus.READY);
    ready.setSummary("Already written.");
    repository.save(ready);

    assertThat(sweepReturning("Overwritten!").sweep()).isZero();
    assertThat(repository.findById(ready.getId()).orElseThrow().getSummary())
        .isEqualTo("Already written.");
  }
}
