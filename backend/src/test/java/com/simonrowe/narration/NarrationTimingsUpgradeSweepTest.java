package com.simonrowe.narration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NarrationTimingsUpgradeSweepTest {

  private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
  private static final NarrationTimings TIMINGS =
      NarrationTimings.of(List.of("Speech."), List.of(1.0));
  private static final NarrationStorage.StoredNarration STORED =
      new NarrationStorage.StoredNarration("/uploads/narrations/n-1/audio-a.mp3", 9, "new", 1,
          "/uploads/narrations/n-1/timings-a.json");

  @Mock private NarrationService narrationService;
  @Mock private NarrationSynthesizer synthesizer;
  @Mock private NarrationProvider provider;
  @Mock private NarrationStorage storage;
  @Mock private NarrationBudgetService budget;

  private Narration narration;
  private NarrationTimingsUpgradeSweep sweep;

  @BeforeEach
  void setUp() {
    narration = new Narration("n-1", NarrationContentType.BLOG, "blog-1", 100, "voice", "en-GB",
        "MP3", "narrations/n-1.mp3", NOW);
    sweep = sweep(true);
  }

  @Test
  void reMakesTheAudioAndSwapsItIn() {
    allowAndDescribe();
    when(synthesizer.synthesize(eq("Speech."), any()))
        .thenReturn(new NarrationSynthesizer.Synthesis(new byte[]{1}, TIMINGS));
    when(storage.store("n-1", new byte[]{1}, TIMINGS)).thenReturn(STORED);
    when(narrationService.replaceAudio(eq(narration), eq(STORED), any())).thenReturn(true);

    sweep.upgrade(narration, NOW);

    verify(storage, never()).delete(any(NarrationStorage.StoredNarration.class));
    verify(narrationService, never()).recordFailedUpgrade(any(), any(Boolean.class), any());
  }

  @Test
  void waitsWhileTheMonthIsOverItsShareOfTheBudget() {
    when(budget.allowsResynthesis(eq(narration), anyDouble(), any())).thenReturn(false);

    sweep.upgrade(narration, NOW);

    verify(synthesizer, never()).synthesize(any(), any());
    verify(narrationService, never()).recordFailedUpgrade(any(), any(Boolean.class), any());
  }

  @Test
  void countsAnAttemptWithoutPayingWhenTheContentHasMovedOn() {
    when(budget.allowsResynthesis(eq(narration), anyDouble(), any())).thenReturn(true);
    when(narrationService.descriptor(NarrationContentType.BLOG, "blog-1"))
        .thenReturn(new NarrationSource.NarrationDescriptor("a-newer-fingerprint", "New."));

    sweep.upgrade(narration, NOW);

    verify(synthesizer, never()).synthesize(any(), any());
    verify(narrationService).recordFailedUpgrade(narration, false, NOW);
  }

  @Test
  void countsTheSpendWhenSynthesisFails() {
    allowAndDescribe();
    when(synthesizer.synthesize(eq("Speech."), any())).thenThrow(
        new NarrationProviderException("boom", FailureKind.SAFE_TO_RETRY, null));

    sweep.upgrade(narration, NOW);

    verify(narrationService).recordFailedUpgrade(narration, true, NOW);
  }

  @Test
  void deletesItsOwnFilesWhenTheNarrationMovedOnMeanwhile() {
    allowAndDescribe();
    when(synthesizer.synthesize(eq("Speech."), any()))
        .thenReturn(new NarrationSynthesizer.Synthesis(new byte[]{1}, TIMINGS));
    when(storage.store("n-1", new byte[]{1}, TIMINGS)).thenReturn(STORED);
    when(narrationService.replaceAudio(eq(narration), eq(STORED), any())).thenReturn(false);
    when(narrationService.plays("n-1", STORED.publicPath())).thenReturn(false);

    sweep.upgrade(narration, NOW);

    verify(storage).delete(STORED);
    verify(narrationService).recordFailedUpgrade(narration, true, NOW);
  }

  /** A regeneration that produced identical bytes has identical file names. */
  @Test
  void keepsTheFilesWhenRegenerationNowPlaysThem() {
    allowAndDescribe();
    when(synthesizer.synthesize(eq("Speech."), any()))
        .thenReturn(new NarrationSynthesizer.Synthesis(new byte[]{1}, TIMINGS));
    when(storage.store("n-1", new byte[]{1}, TIMINGS)).thenReturn(STORED);
    when(narrationService.replaceAudio(eq(narration), eq(STORED), any())).thenReturn(false);
    when(narrationService.plays("n-1", STORED.publicPath())).thenReturn(true);

    sweep.upgrade(narration, NOW);

    verify(storage, never()).delete(any(NarrationStorage.StoredNarration.class));
  }

  @Test
  void deletesRetiredAudioEvenWhileTheUpgradeIsSwitchedOff() {
    Narration retired = narration;
    when(narrationService.retiredBefore(any())).thenReturn(List.of(retired));

    sweep(false).sweep();

    verify(narrationService).deleteRetired(retired);
    verify(narrationService, never()).nextWithoutTimings(any(Integer.class));
  }

  @Test
  void upgradesTheNextNarrationWhenSwitchedOn() {
    when(narrationService.retiredBefore(any())).thenReturn(List.of());
    when(provider.isConfigured()).thenReturn(true);
    when(synthesizer.available()).thenReturn(true);
    when(narrationService.nextWithoutTimings(3)).thenReturn(Optional.of(narration));
    when(budget.allowsResynthesis(eq(narration), anyDouble(), any())).thenReturn(false);

    sweep.sweep();

    verify(budget).allowsResynthesis(eq(narration), eq(0.5), any());
  }

  private void allowAndDescribe() {
    when(budget.allowsResynthesis(eq(narration), anyDouble(), any())).thenReturn(true);
    when(narrationService.descriptor(NarrationContentType.BLOG, "blog-1"))
        .thenReturn(new NarrationSource.NarrationDescriptor("n-1", "Speech."));
  }

  private NarrationTimingsUpgradeSweep sweep(final boolean enabled) {
    return new NarrationTimingsUpgradeSweep(narrationService, synthesizer, provider, storage,
        budget, new NarrationTimingsProperties(4, 3, enabled, 0.5, Duration.ofMinutes(5), 3));
  }
}
