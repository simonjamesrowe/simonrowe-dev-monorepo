package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NarrationSynthesizerTest {

  private static final String FIRST = "The first sentence is long enough to stand alone.";
  private static final String SECOND = "The second sentence is also long enough to stand.";
  private static final String THIRD = "And the third sentence closes the whole narration.";
  private static final String SCRIPT = FIRST + " " + SECOND + " " + THIRD;

  @Test
  void synthesisesEachSentenceAndJoinsTheAudioInScriptOrder() {
    // Ten, twenty and thirty frames, and the first is the slowest to arrive, so finishing
    // order and script order differ under concurrency.
    FakeProvider provider = new FakeProvider(Map.of(FIRST, 10, SECOND, 20, THIRD, 30));
    provider.delayMillis.put(FIRST, 150);
    AtomicInteger progress = new AtomicInteger();

    NarrationSynthesizer.Synthesis synthesis =
        synthesizer(provider, 3, 1).synthesize(SCRIPT, progress::incrementAndGet);

    assertThat(synthesis.audio()).hasSize(60 * 96);
    assertThat(synthesis.audio()[0]).isEqualTo((byte) 0xff);
    assertThat(provider.calls).hasValue(3);
    assertThat(progress).hasValue(2);
    // Sentence two starts after sentence one's 0.24s of audio, plus Google's 0.4s lead-in;
    // capped at a quarter of the 0.48s clip, that lead-in is 0.12s.
    NarrationTimings.Word secondStart = synthesis.timings().words().stream()
        .filter(word -> word.n() == 1).findFirst().orElseThrow();
    assertThat(secondStart.w()).isEqualTo("The");
    assertThat(secondStart.s()).isEqualTo(0.36);
    assertThat(synthesis.timings().words()).hasSize(SCRIPT.split(" ").length);
  }

  @Test
  void retriesSentenceThatFailedTransiently() {
    FakeProvider provider = new FakeProvider(Map.of(FIRST, 1, SECOND, 1, THIRD, 1));
    provider.failuresLeft.put(SECOND, 1);

    NarrationSynthesizer.Synthesis synthesis =
        synthesizer(provider, 1, 2).synthesize(SCRIPT, () -> { });

    assertThat(synthesis.audio()).hasSize(3 * 96);
    assertThat(provider.calls).hasValue(4);
  }

  @Test
  void givesUpWhenTheRetriesRunOut() {
    FakeProvider provider = new FakeProvider(Map.of(FIRST, 1, SECOND, 1, THIRD, 1));
    provider.failuresLeft.put(SECOND, 5);

    assertThatThrownBy(() -> synthesizer(provider, 1, 2).synthesize(SCRIPT, () -> { }))
        .isInstanceOf(NarrationProviderException.class)
        .extracting(ex -> ((NarrationProviderException) ex).kind())
        .isEqualTo(FailureKind.SAFE_TO_RETRY);
  }

  @Test
  void neverRetriesFailureThatIsNotSafeToRetry() {
    FakeProvider provider = new FakeProvider(Map.of(FIRST, 1, SECOND, 1, THIRD, 1));
    provider.failuresLeft.put(FIRST, 1);
    provider.failureKind = FailureKind.UNAVAILABLE;

    assertThatThrownBy(() -> synthesizer(provider, 1, 3).synthesize(SCRIPT, () -> { }))
        .isInstanceOf(NarrationProviderException.class);
    assertThat(provider.callsFor(FIRST)).isEqualTo(1);
  }

  private static NarrationSynthesizer synthesizer(
      final FakeProvider provider, final int concurrency, final int attempts) {
    return new NarrationSynthesizer(provider, new NarrationScriptChunker(),
        new NarrationTimingsProperties(concurrency, attempts, false, 0.5,
            Duration.ofMinutes(5), 3));
  }

  /** Returns a given number of Google-shaped frames per sentence. */
  private static final class FakeProvider implements NarrationProvider {

    private final Map<String, Integer> frames;
    private final Map<String, Integer> delayMillis = new ConcurrentHashMap<>();
    private final Map<String, Integer> failuresLeft = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perSentence = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private FailureKind failureKind = FailureKind.SAFE_TO_RETRY;

    private FakeProvider(final Map<String, Integer> frames) {
      this.frames = frames;
    }

    private int callsFor(final String sentence) {
      return perSentence.getOrDefault(sentence, new AtomicInteger()).get();
    }

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public int maxImmediateBytes() {
      return 5000;
    }

    @Override
    public byte[] synthesizeImmediately(final String script) {
      calls.incrementAndGet();
      perSentence.computeIfAbsent(script, key -> new AtomicInteger()).incrementAndGet();
      if (failuresLeft.getOrDefault(script, 0) > 0) {
        failuresLeft.merge(script, -1, Integer::sum);
        throw new NarrationProviderException("boom", failureKind, null);
      }
      try {
        Thread.sleep(delayMillis.getOrDefault(script, 0));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      return Mp3FramesTest.googleFrames(frames.get(script));
    }

    @Override
    public StartResult start(final String script, final String outputObject) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OperationResult poll(final String operationName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] download(final String outputObject) {
      throw new UnsupportedOperationException();
    }
  }
}
