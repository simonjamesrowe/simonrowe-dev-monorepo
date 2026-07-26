package com.simonrowe.chat;

import com.simonrowe.observability.LangfuseAttributes;
import com.simonrowe.observability.LangfuseProperties;
import com.simonrowe.observability.LangfuseScore;
import com.simonrowe.observability.LangfuseScoreClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Wraps one chat turn in a Micrometer observation carrying the trace-level fields Langfuse
 * needs: session id, input and output.
 *
 * <p>Langfuse's OTLP ingestion applies these to the trace even though this span is not the
 * trace root — its {@code hasTraceUpdates()} check patches the trace from any ingested span
 * carrying {@code session.id}, {@code langfuse.trace.name} or {@code langfuse.trace.input} /
 * {@code .output}. That matters because Alloy's ai_only filter drops the HTTP root span, and
 * without these attributes every trace arrives shallow: unnamed, sessionless and empty.
 *
 * <p>The observation is propagated through the Reactor context the same way Spring AI
 * propagates its own, so generation and tool spans nest underneath it.
 */
@Component
public class ChatTurnTracer {

  static final String OBSERVATION_NAME = "chat-turn";

  private static final Logger LOG = LoggerFactory.getLogger(ChatTurnTracer.class);

  private final ObservationRegistry observationRegistry;
  private final GuardrailVerdictRegistry verdictRegistry;
  private final LangfuseScoreClient scoreClient;
  private final LangfuseProperties properties;

  public ChatTurnTracer(final ObservationRegistry observationRegistry,
      final GuardrailVerdictRegistry verdictRegistry,
      final LangfuseScoreClient scoreClient,
      final LangfuseProperties properties) {
    this.observationRegistry = observationRegistry;
    this.verdictRegistry = verdictRegistry;
    this.scoreClient = scoreClient;
    this.properties = properties;
  }

  /**
   * Traces one chat turn.
   *
   * @param sessionId the chat session id, used as the Langfuse session
   * @param message the visitor's message, recorded as trace input
   * @param source supplies the response stream; invoked on subscription
   * @return the same stream, with observation bookkeeping attached
   */
  public Flux<ChatResponse> trace(final String sessionId, final String message,
      final Supplier<Flux<ChatResponse>> source) {
    return Flux.defer(() -> {
      Observation observation = start(sessionId, message);
      StringBuilder answer = new StringBuilder();
      AtomicInteger toolCalls = new AtomicInteger();
      AtomicBoolean failed = new AtomicBoolean();

      Flux<ChatResponse> stream;
      try {
        stream = source.get();
      } catch (RuntimeException e) {
        // The supplier assembles the chat request eagerly, so it can throw before any operator
        // is attached. Without this the observation would already be started with no doFinally
        // to close it: the span leaks and no scores are submitted. Rethrow afterwards, because
        // this is a chat failure and the subscriber must see it.
        recordError(observation, e);
        finish(observation, sessionId, answer, toolCalls.get(), true);
        throw e;
      }

      return stream
          .doOnNext(response -> accumulate(response, answer, toolCalls))
          .doOnError(error -> {
            failed.set(true);
            recordError(observation, error);
          })
          .doFinally(signal ->
              finish(observation, sessionId, answer, toolCalls.get(), failed.get()))
          .contextWrite(context ->
              context.put(ObservationThreadLocalAccessor.KEY, observation));
    });
  }

  /**
   * Starts the turn observation. A misconfigured handler or observation filter must never break
   * chat, so a failure degrades to {@link Observation#NOOP} rather than reaching the subscriber.
   */
  private Observation start(final String sessionId, final String message) {
    try {
      Observation observation =
          Observation.createNotStarted(OBSERVATION_NAME, observationRegistry);
      observation.highCardinalityKeyValue(LangfuseAttributes.SESSION_ID, nullSafe(sessionId));
      observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_NAME, OBSERVATION_NAME);
      observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_INPUT,
          nullSafe(LangfuseAttributes.truncate(message)));
      observation.highCardinalityKeyValue(LangfuseAttributes.ENVIRONMENT,
          nullSafe(properties.getEnvironment()));
      return observation.start();
    } catch (Exception e) {
      LOG.warn("Failed to start chat turn observation for session {}", sessionId, e);
      return Observation.NOOP;
    }
  }

  /**
   * Records a chat failure on the observation. Guarded because a throw from this telemetry call
   * inside {@code doOnError} would reach the subscriber as a composite exception, mangling the
   * real chat error.
   */
  private static void recordError(final Observation observation, final Throwable error) {
    try {
      observation.error(error);
    } catch (Exception e) {
      LOG.warn("Failed to record a chat turn error on the observation", e);
    }
  }

  private void accumulate(final ChatResponse response, final StringBuilder answer,
      final AtomicInteger toolCalls) {
    if (response == null) {
      return;
    }
    if (response.hasToolCalls()) {
      toolCalls.incrementAndGet();
      return;
    }
    if (response.getResult() != null && response.getResult().getOutput() != null) {
      String text = response.getResult().getOutput().getText();
      if (text != null) {
        answer.append(text);
      }
    }
  }

  private void finish(final Observation observation, final String sessionId,
      final StringBuilder answer, final int toolCalls, final boolean failed) {
    try {
      String text = textOf(answer);
      observation.highCardinalityKeyValue(LangfuseAttributes.TRACE_OUTPUT,
          nullSafe(LangfuseAttributes.truncate(text)));
      String traceId = traceIdOf(observation);
      observation.stop();
      scoreClient.submit(traceId, scoresFor(sessionId, text, toolCalls, failed));
    } catch (Exception e) {
      LOG.warn("Failed to finalise chat turn observation for session {}", sessionId, e);
    }
  }

  /**
   * Reads the accumulated answer. Cancellation can be signalled on one thread while a
   * {@code doOnNext} append is still in flight on another, and {@code StringBuilder.toString()}
   * can throw {@code StringIndexOutOfBoundsException} under that race. Degrading to an empty
   * answer keeps {@code stop()} reachable, so a lost answer never becomes a leaked span.
   */
  private static String textOf(final StringBuilder answer) {
    try {
      return answer.toString();
    } catch (RuntimeException e) {
      LOG.warn("Failed to read the accumulated chat answer", e);
      return "";
    }
  }

  private List<LangfuseScore> scoresFor(final String sessionId, final String answer,
      final int toolCalls, final boolean failed) {
    List<LangfuseScore> scores = new ArrayList<>();
    String verdict = verdictRegistry.takeVerdict(sessionId);
    if (verdict != null) {
      scores.add(LangfuseScore.categorical("guardrail", verdict));
    }
    scores.add(LangfuseScore.numeric("tool-call-count", toolCalls));
    scores.add(LangfuseScore.bool("error", failed));
    scores.add(LangfuseScore.bool("empty-answer", answer.isBlank()));
    return scores;
  }

  /**
   * Reads the OTel trace id off the observation. Returns null when no tracing handler is
   * registered, in which case there is no trace to score against.
   */
  private static String traceIdOf(final Observation observation) {
    TracingObservationHandler.TracingContext tracingContext =
        observation.getContext().get(TracingObservationHandler.TracingContext.class);
    if (tracingContext == null || tracingContext.getSpan() == null) {
      return null;
    }
    return tracingContext.getSpan().context().traceId();
  }

  private static String nullSafe(final String value) {
    return value == null ? "" : value;
  }
}
