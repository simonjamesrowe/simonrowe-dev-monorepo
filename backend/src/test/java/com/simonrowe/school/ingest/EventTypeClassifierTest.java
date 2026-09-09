package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.model.SchoolEvent.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventTypeClassifierTest {

  @Test
  @DisplayName("recognises the spellings the school actually uses for INSET")
  void recognisesInset() {
    assertThat(EventTypeClassifier.classify("Inset Day")).isEqualTo(EventType.INSET);
    assertThat(EventTypeClassifier.classify("INSET DAY")).isEqualTo(EventType.INSET);
    assertThat(EventTypeClassifier.classify("Staff training day")).isEqualTo(EventType.INSET);
  }

  @Test
  @DisplayName("'half term ends' is a half term, not a term boundary")
  void halfTermBeatsTermBoundary() {
    // "half term ends" contains "term end". Order of the checks in the classifier is what
    // stops this being filed as a TERM_BOUNDARY, so it is worth pinning.
    assertThat(EventTypeClassifier.classify("Half term ends")).isEqualTo(EventType.HALF_TERM);
    assertThat(EventTypeClassifier.classify("Half-term holiday")).isEqualTo(EventType.HALF_TERM);
  }

  @Test
  @DisplayName("recognises term boundaries in newsletter phrasing as well as calendar phrasing")
  void recognisesTermBoundaries() {
    assertThat(EventTypeClassifier.classify("End of term"))
        .isEqualTo(EventType.TERM_BOUNDARY);
    assertThat(EventTypeClassifier.classify("Children return to school"))
        .isEqualTo(EventType.TERM_BOUNDARY);
  }

  @Test
  @DisplayName("anything unrecognised falls back to OTHER rather than guessing")
  void unknownFallsBackToOther() {
    assertThat(EventTypeClassifier.classify("SEN Coffee Morning")).isEqualTo(EventType.OTHER);
    assertThat(EventTypeClassifier.classify("")).isEqualTo(EventType.OTHER);
    assertThat(EventTypeClassifier.classify(null)).isEqualTo(EventType.OTHER);
  }
}
