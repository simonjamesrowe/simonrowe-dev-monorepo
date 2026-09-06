package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.tour.TourStep;
import com.simonrowe.tour.TourStepRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TourStepNarrationSourceTest {

  private static final NarrationProperties PROPERTIES = new NarrationProperties(
      true, "project", "123456789012", "key", "global", "voice", "en-GB", "bucket",
      50_000, 1_000_000, Duration.ofMillis(1), Duration.ofSeconds(1),
      Duration.ofSeconds(1), Duration.ofSeconds(1));

  private TourStepRepository tourStepRepository;
  private TourStepNarrationSource source;

  private static TourStep step(final String id, final String title, final String description) {
    return new TourStep(id, 1, ".target", title, null, description, "bottom", "/", null);
  }

  private static Narration narrationOf(final String narrationId, final String contentId) {
    return new Narration(narrationId, NarrationContentType.TOUR_STEP, contentId, 40,
        "voice", "en-GB", "MP3", null, Instant.parse("2026-09-05T00:00:00Z"));
  }

  @BeforeEach
  void setUp() {
    tourStepRepository = mock(TourStepRepository.class);
    source = new TourStepNarrationSource(
        tourStepRepository, new NarrationScriptBuilder(), PROPERTIES);
  }

  @Test
  void handlesTourSteps() {
    assertThat(source.contentType()).isEqualTo(NarrationContentType.TOUR_STEP);
  }

  @Test
  void narratesTheStepTitleThenItsDescription() {
    when(tourStepRepository.findById("step-1"))
        .thenReturn(Optional.of(step("step-1", "Ask Simon anything", "Ask about a decision.")));

    NarrationSource.NarrationDescriptor descriptor = source.scriptFor("step-1");

    assertThat(descriptor.script()).isEqualTo("Ask Simon anything. Ask about a decision.");
  }

  @Test
  void stripsTheMarkdownAnAuthorMayHaveUsedInTheCms() {
    when(tourStepRepository.findById("step-1")).thenReturn(Optional.of(
        step("step-1", "Search the evidence", "Search **posts** and [projects](https://x.dev).")));

    assertThat(source.scriptFor("step-1").script())
        .isEqualTo("Search the evidence. Search posts and projects.");
  }

  @Test
  void reWordingProducesDifferentNarration() {
    NarrationSource.NarrationDescriptor before =
        source.scriptForStep(step("step-1", "Title", "Original wording."));
    NarrationSource.NarrationDescriptor after =
        source.scriptForStep(step("step-1", "Title", "Revised wording."));

    // The fingerprint is the narration id, so a copy change orphans the old audio for free.
    assertThat(before.id()).isNotEqualTo(after.id());
  }

  @Test
  void rejectsStepWithNothingToSay() {
    when(tourStepRepository.findById("step-1"))
        .thenReturn(Optional.of(step("step-1", "  ", "   ")));

    // The built script is "." rather than "", because the builder joins title and body with a
    // full stop — so a blank check would pass this through and synthesise a spoken full stop.
    assertThatThrownBy(() -> source.scriptFor("step-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("422");
  }

  @Test
  void rejectsStepWhoseCopyIsPunctuationOnly() {
    when(tourStepRepository.findById("step-1"))
        .thenReturn(Optional.of(step("step-1", "—", "…")));

    assertThatThrownBy(() -> source.scriptFor("step-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("422");
  }

  @Test
  void rejectsStepThatDoesNotExist() {
    when(tourStepRepository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> source.scriptFor("missing"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void treatsNarrationAsStaleOnceTheStepIsReWorded() {
    TourStep original = step("step-1", "Title", "Original wording.");
    Narration narration = narrationOf(source.scriptForStep(original).id(), "step-1");

    when(tourStepRepository.findById("step-1")).thenReturn(Optional.of(original));
    assertThat(source.isCurrent(narration)).isTrue();

    when(tourStepRepository.findById("step-1"))
        .thenReturn(Optional.of(step("step-1", "Title", "Revised wording.")));
    assertThat(source.isCurrent(narration)).isFalse();
  }

  @Test
  void treatsNarrationAsStaleOnceTheStepIsDeleted() {
    Narration narration = narrationOf("some-fingerprint", "step-1");
    when(tourStepRepository.findById("step-1")).thenReturn(Optional.empty());

    assertThat(source.isCurrent(narration)).isFalse();
  }
}
