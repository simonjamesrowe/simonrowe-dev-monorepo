package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NarrationTimingsTest {

  @Test
  void placesEachSentenceAtTheEndOfTheOneBeforeItLessGooglesLeadIn() {
    NarrationTimings timings = NarrationTimings.of(
        List.of("Alpha beta.", "Gamma delta epsilon."),
        List.of(3.0, 4.0));

    assertThat(timings.words()).extracting(NarrationTimings.Word::w)
        .containsExactly("Alpha", "beta.", "Gamma", "delta", "epsilon.");
    assertThat(timings.words()).extracting(NarrationTimings.Word::n)
        .containsExactly(0, 0, 1, 1, 1);
    assertThat(timings.words().get(0).s()).isEqualTo(0.4);
    assertThat(timings.words().get(2).s()).isEqualTo(3.4);
  }

  @Test
  void spreadsWordsAcrossTheirSentenceByLength() {
    NarrationTimings timings = NarrationTimings.of(
        List.of("a extraordinarily b"), List.of(10.6));

    // Speech runs 0.4s to 10.4s. "a" weighs 2.5, "extraordinarily" 16.5, "b" 2.5.
    List<Double> starts = timings.words().stream().map(NarrationTimings.Word::s).toList();
    assertThat(starts).containsExactly(0.4, 1.56, 9.24);
  }

  @Test
  void givesClauseEndTheTimeOfItsPause() {
    NarrationTimings withComma = NarrationTimings.of(List.of("one, two"), List.of(2.0));
    NarrationTimings without = NarrationTimings.of(List.of("one two"), List.of(2.0));

    assertThat(withComma.words().get(1).s()).isGreaterThan(without.words().get(1).s());
  }

  @Test
  void givesDigitsTheTimeTheyTakeToSay() {
    // "2026" is "twenty twenty-six", far longer than four letters.
    NarrationTimings number = NarrationTimings.of(List.of("in 2026 then"), List.of(3.0));
    NarrationTimings word = NarrationTimings.of(List.of("in town then"), List.of(3.0));

    assertThat(number.words().get(2).s()).isGreaterThan(word.words().get(2).s());
  }

  @Test
  void keepsSomeSpeechInClipShorterThanGooglesUsualSilence() {
    NarrationTimings timings = NarrationTimings.of(List.of("Hi there"), List.of(0.4));

    // A quarter each for lead-in and tail, so speech starts at 0.1s rather than past the end.
    assertThat(timings.words().get(0).s()).isEqualTo(0.1);
    assertThat(timings.words().get(1).s()).isLessThan(0.4);
  }

  @Test
  void refusesSegmentsWithoutDurations() {
    assertThatThrownBy(() -> NarrationTimings.of(List.of("a", "b"), List.of(1.0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesTheCompactShapeThePlayerReads() {
    JsonNode json = JsonMapper.builder().build().readTree(
        NarrationTimings.of(List.of("Say \"hi\"."), List.of(1.0)).toJson());

    assertThat(json.get("version").asInt()).isEqualTo(1);
    assertThat(json.get("words")).hasSize(2);
    JsonNode second = json.get("words").get(1);
    assertThat(second.get("w").asString()).isEqualTo("\"hi\".");
    assertThat(second.get("s").asDouble()).isEqualTo(0.56);
    assertThat(second.get("n").asInt()).isZero();
    assertThat(second.properties()).hasSize(3);
  }
}
