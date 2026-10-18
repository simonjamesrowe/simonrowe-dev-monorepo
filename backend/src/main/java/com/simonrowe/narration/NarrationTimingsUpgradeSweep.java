package com.simonrowe.narration;

import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-makes audio made before read-along timings existed, one narration per tick, newest first.
 *
 * <p>Re-making costs text-to-speech characters, and the monthly budget is sized to Google's free
 * tier. So this spends only while the month's total is under
 * {@link NarrationTimingsProperties#upgradeBudgetShare()} of the limit, leaving the rest for
 * audio a reader asks for. The backlog therefore clears over weeks rather than in one burst,
 * newest first because recent items are the ones most likely to be played. Old audio stays
 * playable throughout; it simply has no highlight until its turn comes.
 *
 * <p>The narration stays {@code READY} the whole time and is swapped in one conditional update
 * ({@link NarrationService#replaceAudio}). The audio it replaces is retired, not deleted, for
 * {@link #RETIREMENT}: somebody may be halfway through it, and a player fetches audio in ranges
 * as it goes, so deleting it would cut them off mid-sentence.
 *
 * <p>Safe only because production runs one backend and {@code fixedDelay} cannot overlap a tick
 * with the one before. Two instances would re-make the same narration twice.
 */
@Component
public class NarrationTimingsUpgradeSweep {

  private static final Logger LOG = LoggerFactory.getLogger(NarrationTimingsUpgradeSweep.class);

  /** How long replaced audio stays on disk. Longer than anybody takes to finish one. */
  static final Duration RETIREMENT = Duration.ofHours(6);

  private final NarrationService narrationService;
  private final NarrationSynthesizer synthesizer;
  private final NarrationProvider provider;
  private final NarrationStorage storage;
  private final NarrationBudgetService budget;
  private final NarrationTimingsProperties properties;

  public NarrationTimingsUpgradeSweep(
      final NarrationService narrationService,
      final NarrationSynthesizer synthesizer,
      final NarrationProvider provider,
      final NarrationStorage storage,
      final NarrationBudgetService budget,
      final NarrationTimingsProperties properties
  ) {
    this.narrationService = narrationService;
    this.synthesizer = synthesizer;
    this.provider = provider;
    this.storage = storage;
    this.budget = budget;
    this.properties = properties;
  }

  @Scheduled(
      initialDelayString = "${narration.timings.upgrade-interval:5m}",
      fixedDelayString = "${narration.timings.upgrade-interval:5m}")
  public void sweep() {
    Instant now = Instant.now();
    for (Narration retired : narrationService.retiredBefore(now.minus(RETIREMENT))) {
      narrationService.deleteRetired(retired);
    }
    if (!properties.upgradeEnabled() || !provider.isConfigured() || !synthesizer.available()) {
      return;
    }
    narrationService.nextWithoutTimings(properties.upgradeMaxAttempts())
        .ifPresent(narration -> upgrade(narration, now));
  }

  /**
   * Re-makes one narration's audio with timings.
   *
   * @param narration a {@code READY} narration with no timings
   * @param now the current instant
   */
  void upgrade(final Narration narration, final Instant now) {
    if (!budget.allowsResynthesis(narration, properties.upgradeBudgetShare(), now)) {
      LOG.debug("Timings upgrade waiting for budget: narrationId={}", narration.id());
      return;
    }
    Optional<String> script = currentScript(narration);
    if (script.isEmpty()) {
      // Its content has changed or gone, so this audio is on its way to STALE. Counting the
      // attempt stops the sweep picking it again every tick until then.
      narrationService.recordFailedUpgrade(narration, false, now);
      return;
    }
    NarrationSynthesizer.Synthesis synthesis;
    try {
      synthesis = synthesizer.synthesize(script.get(), () -> { });
    } catch (NarrationProviderException ex) {
      LOG.warn("Timings upgrade failed: narrationId={}, kind={}", narration.id(), ex.kind());
      // Characters may already have been spent on the sentences that did succeed.
      narrationService.recordFailedUpgrade(narration, true, now);
      return;
    }
    NarrationStorage.StoredNarration stored;
    try {
      stored = storage.store(narration.id(), synthesis.audio(), synthesis.timings());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      LOG.warn("Timings upgrade could not store audio: narrationId={}", narration.id(), ex);
      narrationService.recordFailedUpgrade(narration, true, now);
      return;
    }
    if (narrationService.replaceAudio(narration, stored, Instant.now())) {
      LOG.info("Narration re-made with timings: narrationId={}, characters={}",
          narration.id(), narration.scriptCharacterCount());
    } else {
      // Invalidated or regenerated while this ran. Its new audio is not ours to replace, and
      // the files just written go, unless a regeneration produced the very same bytes and so
      // the very same content-addressed names.
      if (!narrationService.plays(narration.id(), stored.publicPath())) {
        storage.delete(stored);
      }
      narrationService.recordFailedUpgrade(narration, true, now);
    }
  }

  private Optional<String> currentScript(final Narration narration) {
    try {
      NarrationSource.NarrationDescriptor descriptor =
          narrationService.descriptor(narration.contentType(), narration.contentId());
      return descriptor.id().equals(narration.id())
          ? Optional.of(descriptor.script()) : Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }
}
