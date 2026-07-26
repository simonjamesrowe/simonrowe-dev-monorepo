package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ChatTurnTracerTest {

  private TestObservationRegistry observationRegistry;
  private GuardrailVerdictRegistry verdictRegistry;
  private LangfuseScoreClient scoreClient;
  private StopRecorder stopRecorder;
  private ChatTurnTracer tracer;

  @BeforeEach
  void setUp() {
    observationRegistry = TestObservationRegistry.create();
    stopRecorder = new StopRecorder();
    observationRegistry.observationConfig().observationHandler(stopRecorder);
    verdictRegistry = new GuardrailVerdictRegistry();
    scoreClient = mock(LangfuseScoreClient.class);
    LangfuseProperties properties = new LangfuseProperties();
    properties.setEnvironment("test");
    tracer = new ChatTurnTracer(observationRegistry, verdictRegistry, scoreClient, properties);
  }

  /**
   * Records stop signals. The tck exposes no assertion for whether an observation was stopped,
   * and "the span must never leak" is the constraint most worth asserting directly.
   */
  private static final class StopRecorder implements ObservationHandler<Observation.Context> {

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

    @Override
    public void onStop(final Observation.Context context) {
      stopped.add(context);
    }

    @Override
    public boolean supportsContext(final Observation.Context context) {
      return true;
    }
  }

  /** Fails on start, standing in for a misconfigured observation handler or filter. */
  private static final class ExplodingHandler
      implements ObservationHandler<Observation.Context> {

    @Override
    public void onStart(final Observation.Context context) {
      throw new IllegalStateException("handler misconfigured");
    }

    @Override
    public boolean supportsContext(final Observation.Context context) {
      return true;
    }
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
  private List<Observation.Context> handledContexts() {
    List<Observation.Context> handled = new ArrayList<>();
    TestObservationRegistryAssert.assertThat(observationRegistry)
        .hasHandledContextsThatSatisfy(handled::addAll);
    return handled;
  }

  private KeyValues keyValuesOfSingleObservation() {
    List<Observation.Context> handled = handledContexts();
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

    // Asserted rather than caught: a swallowed failure would make blockLast() return null and a
    // bare try/catch would then pass, so the catch could never fail for the claimed behaviour.
    assertThatThrownBy(() -> tracer.trace("session-1", "Hi", failing).blockLast())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("model down");

    assertThat(submittedScores()).contains(LangfuseScore.bool("error", true));
    assertThat(stopRecorder.stopped).hasSize(1);
  }

  @Test
  void stopsTheObservationAndPropagatesWhenTheSupplierThrowsSynchronously() {
    Supplier<Flux<ChatResponse>> throwing = () -> {
      throw new IllegalStateException("request assembly failed");
    };

    assertThatThrownBy(() -> tracer.trace("session-1", "Hi", throwing).blockLast())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("request assembly failed");

    assertThat(stopRecorder.stopped).hasSize(1);
    assertThat(submittedScores()).contains(LangfuseScore.bool("error", true));
  }

  @Test
  void stopsTheObservationWhenTheSubscriptionIsCancelled() {
    Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();

    Disposable subscription = tracer.trace("session-1", "Hi", sink::asFlux).subscribe();
    sink.tryEmitNext(responseWith("partial"));
    subscription.dispose();

    assertThat(stopRecorder.stopped).hasSize(1);
    assertThat(valueOf(LangfuseAttributes.TRACE_OUTPUT)).isEqualTo("partial");
    assertThat(submittedScores()).contains(LangfuseScore.bool("error", false));
  }

  @Test
  void startsOneObservationPerSubscription() {
    Flux<ChatResponse> traced = tracer.trace("session-1", "Hi", streamOf(responseWith("a")));

    traced.blockLast();
    traced.blockLast();

    assertThat(handledContexts()).hasSize(2);
    assertThat(stopRecorder.stopped).hasSize(2);
  }

  @Test
  void swallowsObservationStartFailuresAndStillStreamsTheAnswer() {
    observationRegistry.observationConfig().observationHandler(new ExplodingHandler());

    List<ChatResponse> seen = tracer
        .trace("session-1", "Hi", streamOf(responseWith("a")))
        .collectList()
        .block();

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).getResult().getOutput().getText()).isEqualTo("a");
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
