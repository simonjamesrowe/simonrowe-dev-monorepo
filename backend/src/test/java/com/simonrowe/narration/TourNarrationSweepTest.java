package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.tour.TourStep;
import com.simonrowe.tour.TourStepRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TourNarrationSweepTest {

  private TourStepRepository tourStepRepository;
  private NarrationService narrationService;
  private TourNarrationSweep sweep;

  private static TourStep step(final String id, final int order) {
    return new TourStep(id, order, ".target", "Title " + id, null, "Body.", "bottom", "/", null);
  }

  /**
   * Builds a real response rather than a mock: stubbing one mock inside another's
   * {@code thenReturn} is nested stubbing, which Mockito rejects.
   */
  private static NarrationService.RequestResult result(
      final NarrationResponse.PublicState state, final boolean accepted) {
    return new NarrationService.RequestResult(
        new NarrationResponse(state, 1, null, null, false, "test"), accepted);
  }

  @BeforeEach
  void setUp() {
    tourStepRepository = mock(TourStepRepository.class);
    narrationService = mock(NarrationService.class);
    sweep = new TourNarrationSweep(tourStepRepository, narrationService);
  }

  @Test
  void requestsNarrationForEveryTourStep() {
    when(tourStepRepository.findAllByOrderByOrderAsc())
        .thenReturn(List.of(step("a", 1), step("b", 2)));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("a")))
        .thenReturn(result(NarrationResponse.PublicState.QUEUED, true));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("b")))
        .thenReturn(result(NarrationResponse.PublicState.QUEUED, true));

    assertThat(sweep.sweep()).isEqualTo(2);
    verify(narrationService).request(NarrationContentType.TOUR_STEP, "a");
    verify(narrationService).request(NarrationContentType.TOUR_STEP, "b");
  }

  @Test
  void countsAnAlreadyRenderedStepAsNoNewWork() {
    when(tourStepRepository.findAllByOrderByOrderAsc()).thenReturn(List.of(step("a", 1)));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("a")))
        .thenReturn(result(NarrationResponse.PublicState.READY, false));

    // Re-running the sweep must not re-synthesise anything, which is what makes it safe to
    // run at every startup against a shared monthly character budget.
    assertThat(sweep.sweep()).isZero();
  }

  @Test
  void carriesOnAfterStepThatCannotBeNarrated() {
    when(tourStepRepository.findAllByOrderByOrderAsc())
        .thenReturn(List.of(step("bad", 1), step("good", 2)));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("bad")))
        .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "empty"));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("good")))
        .thenReturn(result(NarrationResponse.PublicState.QUEUED, true));

    assertThat(sweep.sweep()).isEqualTo(1);
    verify(narrationService).request(NarrationContentType.TOUR_STEP, "good");
  }

  @Test
  void neverStopsTheApplicationStartingWhenTextToSpeechIsUnconfigured() {
    when(tourStepRepository.findAllByOrderByOrderAsc()).thenReturn(List.of(step("a", 1)));
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("a")))
        .thenReturn(result(NarrationResponse.PublicState.UNAVAILABLE, false));

    assertThatCode(sweep::onApplicationReady).doesNotThrowAnyException();
    assertThat(sweep.sweep()).isZero();
    verify(narrationService, times(2)).request(NarrationContentType.TOUR_STEP, "a");
  }

  @Test
  void discardsStaleAudioBeforeRenderingReplacementCopy() {
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("a")))
        .thenReturn(result(NarrationResponse.PublicState.QUEUED, true));

    sweep.refresh("a");

    // Order matters: the old READY row is what the bulk ready read would otherwise serve, so
    // it must be gone before the step is offered again — briefly silent, never briefly wrong.
    InOrder inOrder = inOrder(narrationService);
    inOrder.verify(narrationService).invalidate(NarrationContentType.TOUR_STEP, "a");
    inOrder.verify(narrationService).request(NarrationContentType.TOUR_STEP, "a");
  }

  @Test
  void neverFailsAnOperatorSaveBecauseNarrationCouldNotBeRefreshed() {
    when(narrationService.request(eq(NarrationContentType.TOUR_STEP), eq("a")))
        .thenThrow(new IllegalStateException("text-to-speech is down"));

    assertThatCode(() -> sweep.refresh("a")).doesNotThrowAnyException();
  }

  @Test
  void discardsTheAudioOfDeletedStep() {
    sweep.discard("a");

    verify(narrationService).invalidate(NarrationContentType.TOUR_STEP, "a");
    verify(narrationService, never()).request(eq(NarrationContentType.TOUR_STEP), eq("a"));
  }

  @Test
  void doesNothingWhenThereIsNoTour() {
    when(tourStepRepository.findAllByOrderByOrderAsc()).thenReturn(List.of());

    assertThat(sweep.sweep()).isZero();
  }
}
