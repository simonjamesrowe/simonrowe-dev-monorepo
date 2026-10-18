package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link NarrationScriptChunker#segments}: one synthesis request per sentence. */
class NarrationScriptChunkerSegmentsTest {

  private final NarrationScriptChunker chunker = new NarrationScriptChunker();

  @Test
  void splitsAtEverySentenceEnd() {
    String script = """
        The traces follow the GenAI conventions. Do they work everywhere? \
        They work in Grafana Cloud and Honeycomb! The beta is open to everybody who wants it.""";

    assertThat(chunker.segments(script, 5000)).containsExactly(
        "The traces follow the GenAI conventions.",
        "Do they work everywhere? They work in Grafana Cloud and Honeycomb!",
        "The beta is open to everybody who wants it.");
  }

  @Test
  void joinsShortSentenceToTheNextRatherThanPayingForPause() {
    assertThat(chunker.segments(
        "Yes. It records each model call and each tool invocation as a trace.", 5000))
        .containsExactly("Yes. It records each model call and each tool invocation as a trace.");
  }

  @Test
  void joinsShortFinalSentenceToTheOneBeforeIt() {
    assertThat(chunker.segments(
        "It records each model call and each tool invocation as a trace. Done.", 5000))
        .containsExactly(
            "It records each model call and each tool invocation as a trace. Done.");
  }

  @Test
  void doesNotSplitAfterAnAbbreviationOrAnInitial() {
    String script = """
        Some providers, e.g. Langfuse and Arize, read these traces natively. \
        Written by J. Smith and checked by Dr. Jones before it was published.""";

    assertThat(chunker.segments(script, 5000)).containsExactly(
        "Some providers, e.g. Langfuse and Arize, read these traces natively.",
        "Written by J. Smith and checked by Dr. Jones before it was published.");
  }

  @Test
  void cutsSentenceLongerThanTheRequestCeiling() {
    String sentence = "word ".repeat(30).trim() + ".";

    List<String> segments = chunker.segments(sentence, 40);

    assertThat(segments).hasSizeGreaterThan(1)
        .allSatisfy(segment -> assertThat(NarrationScriptChunker.utf8Length(segment))
            .isLessThanOrEqualTo(40));
  }

  @Test
  void joiningTheSegmentsGivesBackTheScript() {
    String script = """
        Anthropic introduced tracing. It covers turns, model calls and tools. \
        Traces follow OpenTelemetry's GenAI conventions, so they sit beside the rest. \
        Ok. The capability is in public beta, and SDKs export traces automatically.""";

    assertThat(String.join(" ", chunker.segments(script, 5000))).isEqualTo(script);
  }

  @Test
  void returnsNothingForBlankScript() {
    assertThat(chunker.segments("  ", 5000)).isEmpty();
  }

  /** The script is unbounded input. A worst-case shape must not backtrack catastrophically. */
  @Test
  void handlesHugeRunOfPunctuationAndSpaceInLinearTime() {
    String hostile = ".".repeat(60_000) + " ".repeat(60_000) + "end";

    assertThat(chunker.segments(hostile, 5000)).isNotEmpty();
  }
}
