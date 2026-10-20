package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.events.ContentChangeEvent;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import com.simonrowe.events.SummaryRequestEvent;
import com.simonrowe.meals.MealsModel;
import com.simonrowe.summary.ArticleSummaryFailure;
import com.simonrowe.summary.ArticleSummaryResponse;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryRequestConsumer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real Mongo/Kafka, the production starter and bridge, and no cloud exporter. */
@Import(TracingIntegrationTest.CaptureConfiguration.class)
class TracingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OpenTelemetry telemetry;
  @Autowired private MealsModel meals;
  @Autowired private SummaryRequestConsumer summaryConsumer;
  @Autowired private BlogRepository blogs;
  @Autowired private KafkaTemplate<String, ContentChangeEvent> kafka;
  @Autowired private RecordingProcessor recording;
  @MockitoBean private ArticleSummaryService summaries;

  @BeforeEach
  void prepareControlledSummaryResults() {
    when(summaries.request(anyString())).thenAnswer(invocation -> {
      String id = invocation.getArgument(0);
      if ("trace-failure".equals(id)) {
        throw new IllegalStateException("private-provider-message-not-exported");
      }
      return new ArticleSummaryService.RequestResult(new ArticleSummaryResponse(
          ArticleSummaryResponse.PublicState.READY, 1, null, null, null,
          null, false, null), false);
    });
  }

  @Test
  void repositoryIntentParentsTheRealMongoCommandWithoutRecordingArguments() {
    Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.request").startSpan();
    String marker = "private-query-argument-must-not-be-a-tag";
    try (Scope ignored = request.makeCurrent()) {
      blogs.findByIdAndPublishedTrue(marker);
    } finally {
      request.end();
    }
    List<SpanData> trace = trace(request);
    SpanData repository = trace.stream().filter(span ->
        "BlogRepository.findByIdAndPublishedTrue".equals(span.getName())).findFirst().orElseThrow();
    assertThat(repository.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
    assertThat(repository.getAttributes().get(AttributeKey.stringKey("app.query.name")))
        .isEqualTo("blogs.findByIdAndPublishedTrue");
    assertThat(trace).anySatisfy(span -> {
      assertThat(span.getInstrumentationScopeInfo().getName()).contains("mongo");
      assertThat(span.getParentSpanId()).isEqualTo(repository.getSpanId());
    });
    assertThat(repository.getAttributes().toString()).doesNotContain(marker);
    assertThat(trace.stream().map(SpanData::getSpanId)).doesNotHaveDuplicates();
  }

  @Test
  void kafkaCarriesOneRequestThroughTheProducerConsumerAndDatabase() {
    Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.enqueue")
        .setSpanKind(SpanKind.SERVER).startSpan();
    try (Scope ignored = request.makeCurrent()) {
      // The base test class mocks this publisher; exercise the real production class here.
      new ContentChangePublisher(kafka).publishUpdated(ContentType.BLOG, "trace-success");
    } finally {
      request.end();
    }
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        assertThat(trace(request)).anyMatch(span -> span.getKind() == SpanKind.CONSUMER));
    List<SpanData> trace = trace(request);
    SpanData producer = trace.stream().filter(span -> span.getKind() == SpanKind.PRODUCER)
        .findFirst().orElseThrow();
    SpanData consumer = trace.stream().filter(span -> span.getKind() == SpanKind.CONSUMER)
        .findFirst().orElseThrow();
    assertThat(producer.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
    assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanId());
    assertThat(consumer.getLinks()).isEmpty();
    assertThat(trace).anyMatch(span ->
        "BlogRepository.findByIdAndPublishedTrue".equals(span.getName()));
    assertThat(trace.stream().map(SpanData::getSpanId)).doesNotHaveDuplicates();
  }

  @Test
  void repositoryFailureEndsItsSpanAndRestoresTheCallerScope() {
    Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.invalid-read")
        .startSpan();
    try (Scope ignored = request.makeCurrent()) {
      assertThatThrownBy(() -> blogs.findAll((org.springframework.data.domain.Sort) null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(Span.current().getSpanContext().getSpanId())
          .isEqualTo(request.getSpanContext().getSpanId());
    } finally {
      request.end();
    }
    assertThat(trace(request)).anySatisfy(span -> {
      assertThat(span.getName()).isEqualTo("BlogRepository.findAll");
      assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      assertThat(span.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
    });
  }

  @Test
  void caughtFailureIsAnErrorOnItsOperationWhileTheRequestSucceeded() {
    Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.enqueue").startSpan();
    try (Scope ignored = request.makeCurrent()) {
      new ContentChangePublisher(kafka).publishUpdated(ContentType.BLOG, "trace-failure");
    } finally {
      request.end();
    }
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
        assertThat(trace(request)).anySatisfy(span -> {
          assertThat(span.getName()).isEqualTo("summary.generate");
          assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
          assertThat(span.getAttributes().get(AttributeKey.stringKey("app.outcome")))
              .isEqualTo("failed");
          assertThat(span.getAttributes().get(AttributeKey.stringKey("error.type")))
              .isEqualTo(IllegalStateException.class.getName());
          assertThat(span.getAttributes().toString()).doesNotContain("private-provider-message");
          assertThat(span.getEvents()).isEmpty();
        }));
    assertThat(trace(request)).filteredOn(span ->
        span.getSpanId().equals(request.getSpanContext().getSpanId()))
        .allSatisfy(span -> assertThat(span.getStatus().getStatusCode())
            .isNotEqualTo(StatusCode.ERROR));
  }

  @Test
  void storedFailuresDistinguishModelErrorsFromUnavailableSource() {
    for (String code : List.of(ArticleSummaryFailure.MODEL_ERROR,
        ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT)) {
      when(summaries.request("trace-stored")).thenReturn(new ArticleSummaryService.RequestResult(
          new ArticleSummaryResponse(ArticleSummaryResponse.PublicState.FAILED,
              1, null, null, null, code, false, null), false));
      Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.stored").startSpan();
      try (Scope ignored = request.makeCurrent()) {
        summaryConsumer.handle(new SummaryRequestEvent("trace-stored", Instant.now()));
      } finally {
        request.end();
      }
      SpanData operation = trace(request).stream()
          .filter(span -> "summary.generate".equals(span.getName())).findFirst().orElseThrow();
      if (ArticleSummaryFailure.MODEL_ERROR.equals(code)) {
        assertThat(operation.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      } else {
        assertThat(operation.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(operation.getAttributes().get(AttributeKey.stringKey("app.outcome")))
            .isEqualTo("unavailable");
      }
    }
  }

  @Test
  void theRealModelExecutorPreservesAndClearsContextIncludingAfterFailure() {
    List<String> observedTraceIds = new CopyOnWriteArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    when(chatModel.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenAnswer(invocation -> {
      observedTraceIds.add(Span.current().getSpanContext().getTraceId());
      if (calls.incrementAndGet() == 2) {
        throw new IllegalStateException("controlled provider failure");
      }
      return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
    });
    Span request = telemetry.getTracer("tracing-test").spanBuilder("fixture.executor").startSpan();
    try (Scope ignored = request.makeCurrent()) {
      assertThat(meals.structured("system", "fixture", null, null, "{}")).isEqualTo("{}");
      assertThatThrownBy(() -> meals.structured("system", "fixture", null, null, "{}"))
          .isInstanceOf(IllegalStateException.class);
      assertThat(Span.current().getSpanContext().getTraceId())
          .isEqualTo(request.getSpanContext().getTraceId());
    } finally {
      request.end();
    }
    meals.structured("system", "fixture", null, null, "{}");
    assertThat(observedTraceIds).containsExactly(request.getSpanContext().getTraceId(),
        request.getSpanContext().getTraceId(), "00000000000000000000000000000000");
  }

  private List<SpanData> trace(final Span root) {
    return recording.spans.stream().filter(span ->
        span.getTraceId().equals(root.getSpanContext().getTraceId())).toList();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CaptureConfiguration {
    @Bean
    RecordingProcessor recordingProcessor() {
      return new RecordingProcessor();
    }

    @Bean
    AutoConfigurationCustomizerProvider captureStarter(final RecordingProcessor processor) {
      return customizer -> customizer.addTracerProviderCustomizer((builder, properties) ->
          builder.addSpanProcessor(processor));
    }

    @Bean
    SdkTracerProviderBuilderCustomizer captureBridge(final RecordingProcessor processor) {
      return builder -> builder.addSpanProcessor(processor);
    }

    @Bean
    TraceConsumer traceConsumer(final BlogRepository blogs,
        final SummaryRequestConsumer consumer) {
      return new TraceConsumer(blogs, consumer);
    }
  }

  static class TraceConsumer {
    private final BlogRepository blogs;
    private final SummaryRequestConsumer consumer;

    TraceConsumer(final BlogRepository blogs, final SummaryRequestConsumer consumer) {
      this.blogs = blogs;
      this.consumer = consumer;
    }

    @KafkaListener(topics = "content-changes", id = "tracing-fixture", autoStartup = "true",
        groupId = "tracing-fixture")
    void consume(final ContentChangeEvent event) {
      if (event.contentId().startsWith("trace-")) {
        blogs.findByIdAndPublishedTrue(event.contentId());
        consumer.handle(new SummaryRequestEvent(event.contentId(), Instant.now()));
      }
    }
  }

  static class RecordingProcessor implements SpanProcessor {
    final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public void onStart(final Context parent, final ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(final ReadableSpan span) {
      spans.add(span.toSpanData());
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
