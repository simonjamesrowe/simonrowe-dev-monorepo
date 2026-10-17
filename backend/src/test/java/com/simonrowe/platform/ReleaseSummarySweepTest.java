package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.factoryadmin.ContentWritingClient;
import com.simonrowe.factoryadmin.ContentWritingClient.State;
import com.simonrowe.factoryadmin.ContentWritingClient.WritingJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
    PlatformRelease release = PlatformRelease.fromCommit(
        new MainCommit(
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

  // --- the claude-code writer -------------------------------------------------------------

  private static final String SHA = "840c311abcdef0123456789abcdef0123456789a";
  private static final String JOB_ID = "release-" + SHA + "-platform-release-v1";

  private final ContentWritingClient writingClient = mock(ContentWritingClient.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T09:00:00Z"));

  /** The OpenAI side of a claude-code sweep: answers with {@code completion}, or is never used. */
  private Ai openAiReturning(final String completion) {
    Ai stubAi = mock(Ai.class);
    PromptRunner runner = mock(PromptRunner.class);
    when(stubAi.withLlm(anyString())).thenReturn(runner);
    when(runner.respond(anyList())).thenReturn(new AssistantMessage(completion));
    return stubAi;
  }

  private ReleaseSummarySweep claudeCodeSweep(final Ai stubAi) {
    return new ReleaseSummarySweep(
        repository, stubAi, true, 3, 3, "test-model", "claude-code", writingClient, clock,
        Duration.ofMinutes(30));
  }

  private static WritingJob job(final State state, final String text) {
    return new WritingJob(JOB_ID, state, text);
  }

  private PlatformRelease stored() {
    return repository.findById(SHA).orElseThrow();
  }

  @Test
  void claudeCodeSubmitsAnUnknownJobAndLeavesTheReleasePendingWhileItRuns() {
    pending(SHA, 1756200000L);
    Ai stubAi = openAiReturning("from OpenAI");
    when(writingClient.status(JOB_ID)).thenReturn(job(State.NOT_FOUND, null));
    when(writingClient.submit(eq(JOB_ID), anyString())).thenReturn(job(State.RUNNING, null));

    assertThat(claudeCodeSweep(stubAi).sweep()).isZero();

    verify(writingClient).submit(eq(JOB_ID), contains("Commit subject: feat: add a thing"));
    // Waiting is not a failed attempt: the release must not creep towards FAILED while Claude
    // Code is simply busy.
    assertThat(stored().getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.PENDING);
    assertThat(stored().getSummaryAttempts()).isZero();
    verify(stubAi, never()).withLlm(anyString());
  }

  @Test
  void claudeCodeStoresCompletedTextThroughTheSameReadyPath() {
    pending(SHA, 1756200000L);
    Ai stubAi = openAiReturning("from OpenAI");
    when(writingClient.status(JOB_ID)).thenReturn(job(State.COMPLETED, "x".repeat(5000)));

    ReleaseSummarySweep sweep = claudeCodeSweep(stubAi);
    assertThat(sweep.sweep()).isEqualTo(1);

    assertThat(stored().getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
    assertThat(stored().getSummary()).endsWith("… (truncated)").hasSizeLessThan(5000);
    assertThat(stored().getSummaryAttempts()).isEqualTo(1);
    verify(writingClient, never()).submit(anyString(), anyString());
    verify(stubAi, never()).withLlm(anyString());
    assertThat(sweep.claudeCodeJobsTracked()).isZero();
  }

  @Test
  void claudeCodeStillRunningWithinTheBoundKeepsWaiting() {
    pending(SHA, 1756200000L);
    Ai stubAi = openAiReturning("from OpenAI");
    when(writingClient.status(JOB_ID)).thenReturn(job(State.RUNNING, null));
    ReleaseSummarySweep sweep = claudeCodeSweep(stubAi);

    sweep.sweep();
    clock.advance(Duration.ofMinutes(29));
    sweep.sweep();

    assertThat(stored().getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.PENDING);
    assertThat(stored().getSummaryAttempts()).isZero();
    verify(stubAi, never()).withLlm(anyString());
    // Running jobs are only ever read, never resubmitted.
    verify(writingClient, never()).submit(anyString(), anyString());
  }

  @Test
  void claudeCodeStillRunningPastTheBoundFallsBackToOpenAiInTheSameTick() {
    pending(SHA, 1756200000L);
    Ai stubAi = openAiReturning("from OpenAI");
    when(writingClient.status(JOB_ID)).thenReturn(job(State.RUNNING, null));
    ReleaseSummarySweep sweep = claudeCodeSweep(stubAi);

    sweep.sweep();
    clock.advance(Duration.ofMinutes(30));
    assertThat(sweep.sweep()).isEqualTo(1);

    assertThat(stored().getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.READY);
    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
    assertThat(sweep.claudeCodeJobsTracked()).isZero();
  }

  @Test
  void claudeCodePastTheBoundKeepsFallingBackIfOpenAiAlsoFails() {
    pending(SHA, 1756200000L);
    Ai stubAi = mock(Ai.class);
    when(stubAi.withLlm(anyString())).thenThrow(new IllegalStateException("model down"));
    when(writingClient.status(JOB_ID)).thenReturn(job(State.RUNNING, null));
    ReleaseSummarySweep sweep = claudeCodeSweep(stubAi);

    sweep.sweep();
    clock.advance(Duration.ofMinutes(31));
    sweep.sweep();
    // The bound has already passed for this job, so the next tick does not wait again.
    sweep.sweep();
    sweep.sweep();

    assertThat(stored().getSummaryStatus()).isEqualTo(ReleaseSummaryStatus.FAILED);
    assertThat(stored().getSummaryAttempts()).isEqualTo(3);
    assertThat(sweep.claudeCodeJobsTracked()).isZero();
  }

  @Test
  void claudeCodeFailedFallsBackToOpenAiWithoutResubmitting() {
    pending(SHA, 1756200000L);
    Ai stubAi = openAiReturning("from OpenAI");
    when(writingClient.status(JOB_ID)).thenReturn(job(State.FAILED, null));

    assertThat(claudeCodeSweep(stubAi).sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
    // Submitting would restart the failed job on every tick and burn subscription quota.
    verify(writingClient, never()).submit(anyString(), anyString());
  }

  @Test
  void claudeCodeCompletedWithBlankTextFallsBackToOpenAi() {
    pending(SHA, 1756200000L);
    when(writingClient.status(JOB_ID)).thenReturn(job(State.COMPLETED, "  "));

    assertThat(claudeCodeSweep(openAiReturning("from OpenAI")).sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
  }

  @Test
  void unreachableFactoryFallsBackToOpenAi() {
    pending(SHA, 1756200000L);
    when(writingClient.status(JOB_ID)).thenThrow(new ResourceAccessException("refused"));

    assertThat(claudeCodeSweep(openAiReturning("from OpenAI")).sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
  }

  @Test
  void refusingFactoryFallsBackToOpenAi() {
    // 401 (token mismatch) and 503 (module disabled) both arrive as exceptions from RestClient.
    pending(SHA, 1756200000L);
    when(writingClient.status(JOB_ID))
        .thenThrow(HttpClientErrorException.create(
            HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));
    when(writingClient.submit(anyString(), anyString()))
        .thenThrow(HttpServerErrorException.create(
            HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null));

    assertThat(claudeCodeSweep(openAiReturning("from OpenAI")).sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
  }

  @Test
  void submitThatFailsFallsBackToOpenAi() {
    pending(SHA, 1756200000L);
    when(writingClient.status(JOB_ID)).thenReturn(job(State.NOT_FOUND, null));
    when(writingClient.submit(anyString(), anyString()))
        .thenThrow(HttpServerErrorException.create(
            HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null));

    assertThat(claudeCodeSweep(openAiReturning("from OpenAI")).sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
  }

  @Test
  void theOpenAiWriterNeverTouchesTheFactory() {
    pending(SHA, 1756200000L);
    ReleaseSummarySweep sweep =
        new ReleaseSummarySweep(
            repository, openAiReturning("from OpenAI"), true, 3, 3, "test-model", "openai",
            writingClient, clock, Duration.ofMinutes(30));

    assertThat(sweep.sweep()).isEqualTo(1);

    assertThat(stored().getSummary()).isEqualTo("from OpenAI");
    verifyNoInteractions(writingClient);
  }

  @Test
  void unknownWriterMeansOpenAi() {
    pending(SHA, 1756200000L);
    ReleaseSummarySweep sweep =
        new ReleaseSummarySweep(
            repository, openAiReturning("from OpenAI"), true, 3, 3, "test-model", "claude",
            writingClient, clock, Duration.ofMinutes(30));

    assertThat(sweep.sweep()).isEqualTo(1);

    verifyNoInteractions(writingClient);
  }

  @Test
  void theJobIdStaysWithinTheFactoryCharset() {
    PlatformRelease release = pending(SHA, 1756200000L);

    assertThat(ClaudeCodeReleaseWriter.jobId(release))
        .isEqualTo(JOB_ID)
        .matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  }

  /** A clock the test moves by hand, so the wait bound needs no sleeping. */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(final Instant start) {
      this.now = start;
    }

    void advance(final Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
