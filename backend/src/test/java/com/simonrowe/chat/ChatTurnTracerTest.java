package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.simonrowe.observability.LangfuseAttributes;
import com.simonrowe.observability.LangfuseProperties;
import com.simonrowe.observability.LangfuseScore;
import com.simonrowe.observability.LangfuseScoreClient;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

class ChatTurnTracerTest {

  private TestObservationRegistry observationRegistry;
  private GuardrailVerdictRegistry verdictRegistry;
  private LangfuseScoreClient scoreClient;
  private ChatTurnTracer tracer;

  @BeforeEach
  void setUp() {
    observationRegistry = TestObservationRegistry.create();
    verdictRegistry = new GuardrailVerdictRegistry();
    scoreClient = mock(LangfuseScoreClient.class);
    LangfuseProperties properties = new LangfuseProperties();
    properties.setEnvironment("test");
    tracer = new ChatTurnTracer(observationRegistry, verdictRegistry, scoreClient, properties);
  }

  private static ChatResponse responseWith(final String text) {
    return ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage(text))))
        .build();
  }

  private static Supplier<Flux<ChatResponse>> streamOf(final ChatResponse... responses) {
    return () -> Flux.just(responses);
  }

  /**
   * {@code TestObservationRegistry.getContexts()} is package private, so the handled contexts are
   * read through the public assert API instead.
   */
  private KeyValues keyValuesOfSingleObservation() {
    List<Observation.Context> handled = new ArrayList<>();
    TestObservationRegistryAssert.assertThat(observationRegistry)
        .hasHandledContextsThatSatisfy(handled::addAll);
    assertThat(handled).hasSize(1);
    return handled.get(0).getHighCardinalityKeyValues();
  }

  private String valueOf(final String key) {
    return keyValuesOfSingleObservation().stream()
        .filter(keyValue -> keyValue.getKey().equals(key))
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  @Test
  void recordsSessionIdAndTraceInputOnTheObservation() {
    tracer.trace("session-1", "What is Kafka?", streamOf(responseWith("A log.")))
        .blockLast();

    assertThat(valueOf(LangfuseAttributes.SESSION_ID)).isEqualTo("session-1");
    assertThat(valueOf(LangfuseAttributes.TRACE_INPUT)).isEqualTo("What is Kafka?");
    assertThat(valueOf(LangfuseAttributes.TRACE_NAME)).isEqualTo(ChatTurnTracer.OBSERVATION_NAME);
    assertThat(valueOf(LangfuseAttributes.ENVIRONMENT)).isEqualTo("test");
  }

  @Test
  void recordsTheAssembledAnswerAsTraceOutput() {
    tracer.trace("session-1", "Hello",
            streamOf(responseWith("Hel"), responseWith("lo "), responseWith("there")))
        .blockLast();

    assertThat(valueOf(LangfuseAttributes.TRACE_OUTPUT)).isEqualTo("Hello there");
  }

  @Test
  void passesThroughEveryResponseUnchanged() {
    List<ChatResponse> seen = tracer
        .trace("session-1", "Hi", streamOf(responseWith("a"), responseWith("b")))
        .collectList()
        .block();

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).getResult().getOutput().getText()).isEqualTo("a");
  }

  @Test
  void submitsGuardrailVerdictAsCategoricalScore() {
    verdictRegistry.record("session-1", "OFF_TOPIC");

    tracer.trace("session-1", "Weather?", streamOf(responseWith("I only discuss Simon.")))
        .blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.categorical("guardrail", "OFF_TOPIC"));
  }

  @Test
  void submitsEmptyAnswerScoreWhenNothingWasStreamed() {
    tracer.trace("session-1", "Hi", Flux::<ChatResponse>empty).blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.bool("empty-answer", true));
    assertThat(submittedScores()).contains(LangfuseScore.bool("error", false));
  }

  @Test
  void submitsErrorScoreAndPropagatesTheFailure() {
    Supplier<Flux<ChatResponse>> failing =
        () -> Flux.error(new IllegalStateException("model down"));

    try {
      tracer.trace("session-1", "Hi", failing).blockLast();
    } catch (IllegalStateException expected) {
      // the tracer must not swallow chat failures
    }

    assertThat(submittedScores()).contains(LangfuseScore.bool("error", true));
  }

  @Test
  void submitsToolCallCount() {
    ChatResponse toolCall = ChatResponse.builder()
        .generations(List.of(new Generation(
            AssistantMessage.builder()
                .content("")
                .properties(java.util.Map.of())
                .toolCalls(
                    List.of(new AssistantMessage.ToolCall("id", "function", "getJobs", "{}")))
                .build())))
        .build();

    tracer.trace("session-1", "Jobs?", streamOf(toolCall, responseWith("Here they are")))
        .blockLast();

    assertThat(submittedScores()).contains(LangfuseScore.numeric("tool-call-count", 1));
  }

  @SuppressWarnings("unchecked")
  private List<LangfuseScore> submittedScores() {
    ArgumentCaptor<List<LangfuseScore>> captor = ArgumentCaptor.forClass(List.class);
    verify(scoreClient).submit(any(), captor.capture());
    return captor.getValue();
  }
}
