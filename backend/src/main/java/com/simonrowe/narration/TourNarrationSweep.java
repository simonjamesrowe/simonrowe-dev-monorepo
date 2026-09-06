package com.simonrowe.narration;

import com.simonrowe.tour.TourStep;
import com.simonrowe.tour.TourStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every guided-tour step's spoken audio generated ahead of anyone taking the tour.
 *
 * <p><strong>There is deliberately no public endpoint that starts a tour narration.</strong>
 * A text-to-speech render draws on a monthly character budget shared with blog and summary
 * narration, and the tour is the one narrated surface reachable without signing in — an
 * anonymous POST that triggered synthesis is exactly the drain
 * {@code SummaryNarrationController} authenticates itself to avoid. Generation is therefore
 * driven from inside the application and the browser only ever performs the bulk
 * {@code GET /api/narrations/ready?contentType=TOUR_STEP} read.
 *
 * <p>The cost of that decision is nil: {@code NarrationService.request} reuses any existing
 * {@code READY} narration, and a step's fingerprint is computed over its own copy, so a
 * sweep re-renders only the steps whose wording actually changed since the last one. A full
 * ten-step tour is under two thousand characters against a million-character allowance.
 *
 * <p>Runs once at startup — covering both a fresh seed and a copy change shipped in a
 * deploy — and then daily, which is what picks up a step re-worded in the admin CMS. A step
 * that cannot be narrated (empty copy, or text-to-speech unconfigured) is logged and
 * skipped: the tour must still run silently rather than fail to load.
 */
@Component
public class TourNarrationSweep {

  private static final Logger LOG = LoggerFactory.getLogger(TourNarrationSweep.class);
  private static final long DAILY_MS = 24L * 60 * 60 * 1000;

  private final TourStepRepository tourStepRepository;
  private final NarrationService narrationService;

  public TourNarrationSweep(
      final TourStepRepository tourStepRepository,
      final NarrationService narrationService
  ) {
    this.tourStepRepository = tourStepRepository;
    this.narrationService = narrationService;
  }

  /** Generates any missing tour narration shortly after the application is serving. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    sweep();
  }

  /** Picks up steps re-worded in the admin CMS since the last sweep. */
  @Scheduled(initialDelay = DAILY_MS, fixedDelay = DAILY_MS)
  public void onSchedule() {
    sweep();
  }

  /**
   * Re-renders one step's audio after an operator has changed its copy.
   *
   * <p>Without this the tour would <em>speak the previous wording while displaying the new
   * one</em> until the next sweep, which is worse than silence: the old narration is still
   * {@code READY} under the old fingerprint, and the bulk ready read returns the newest
   * {@code READY} row per content id. Invalidating first deletes that audio, so the step is
   * briefly silent rather than briefly wrong.
   *
   * <p>Never throws. A failure to re-narrate must not fail the operator's save.
   *
   * @param stepId the tour step whose copy changed
   */
  public void refresh(final String stepId) {
    try {
      narrationService.invalidate(NarrationContentType.TOUR_STEP, stepId);
      narrationService.request(NarrationContentType.TOUR_STEP, stepId);
    } catch (RuntimeException ex) {
      LOG.warn("Could not refresh narration for tour step {}: {}", stepId, ex.toString());
    }
  }

  /**
   * Discards the audio of a step that no longer exists.
   *
   * @param stepId the deleted tour step
   */
  public void discard(final String stepId) {
    try {
      narrationService.invalidate(NarrationContentType.TOUR_STEP, stepId);
    } catch (RuntimeException ex) {
      LOG.warn("Could not discard narration for tour step {}: {}", stepId, ex.toString());
    }
  }

  /**
   * Requests narration for every tour step, reusing whatever is already rendered.
   *
   * @return how many steps had new synthesis queued
   */
  public int sweep() {
    int queued = 0;
    int skipped = 0;
    for (TourStep step : tourStepRepository.findAllByOrderByOrderAsc()) {
      try {
        NarrationService.RequestResult result =
            narrationService.request(NarrationContentType.TOUR_STEP, step.id());
        if (result.response().state() == NarrationResponse.PublicState.UNAVAILABLE) {
          skipped++;
        } else if (result.accepted()) {
          queued++;
        }
      } catch (RuntimeException ex) {
        // One unnarratable step must never stop the rest of the tour being narrated, and
        // must never stop the application starting.
        skipped++;
        LOG.warn("Could not queue narration for tour step {}: {}", step.id(), ex.toString());
      }
    }
    if (queued > 0 || skipped > 0) {
      LOG.info("Tour narration sweep queued {} step(s), skipped {}", queued, skipped);
    }
    return queued;
  }
}
