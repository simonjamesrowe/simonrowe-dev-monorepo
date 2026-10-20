package com.simonrowe.summary;

import com.simonrowe.events.SummaryRequestEvent;
import com.simonrowe.observability.OperationOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates the summary for a favourited article, off the request thread.
 *
 * <p>Nothing here needs to be idempotent by hand: {@code ArticleSummaryService.request} is
 * already insert-first, returns an existing {@code READY} summary untouched, and returns a
 * stored non-retryable failure without calling the model again. A redelivered event
 * therefore costs a database read, not a model call — which matters, because roughly a
 * third of the aggregated sources are RSS-only and fail
 * {@code INSUFFICIENT_SOURCE_TEXT} permanently.
 */
@Component
public class SummaryRequestConsumer {

  private static final Logger LOG =
      LoggerFactory.getLogger(SummaryRequestConsumer.class);

  private final ArticleSummaryService summaryService;
  private final MeterRegistry meterRegistry;
  private final ObservationRegistry observationRegistry;

  public SummaryRequestConsumer(
      final ArticleSummaryService summaryService,
      final MeterRegistry meterRegistry,
      final ObservationRegistry observationRegistry
  ) {
    this.summaryService = summaryService;
    this.meterRegistry = meterRegistry;
    this.observationRegistry = observationRegistry;
  }

  @KafkaListener(
      topics = SummaryRequestPublisher.TOPIC,
      groupId = "summary-generator",
      concurrency = "${aggregation.summary.consumer-concurrency:1}"
  )
  public void handle(final SummaryRequestEvent event) {
    Observation.createNotStarted("summary.process", observationRegistry)
        .contextualName("summary.generate")
        .highCardinalityKeyValue("app.operation", "summary.generate")
        .observe(() -> process(event));
  }

  private void process(final SummaryRequestEvent event) {
    try {
      ArticleSummaryService.RequestResult result =
          summaryService.request(event.articleId());
      meterRegistry.counter("article.summary.auto",
          "result", result.response().state().name()).increment();
      switch (result.response().state()) {
        case READY -> OperationOutcome.SUCCESS.record();
        case GENERATING -> OperationOutcome.QUEUED.record();
        case NOT_REQUESTED -> OperationOutcome.SKIPPED.record();
        case FAILED -> {
          if (ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT.equals(
              result.response().failureCode())
              || ArticleSummaryFailure.ARTICLE_NOT_FOUND.equals(result.response().failureCode())) {
            OperationOutcome.UNAVAILABLE.record();
          } else {
            OperationOutcome.FAILED.record();
          }
        }
        default -> throw new IllegalStateException("Unknown summary state");
      }
      LOG.info("Auto-summary for favourited article: articleId={}, state={}",
          event.articleId(), result.response().state());
    } catch (ResponseStatusException ex) {
      if (ex.getStatusCode().is5xxServerError()) {
        OperationOutcome.failed(ex);
      } else {
        OperationOutcome.UNAVAILABLE.record();
      }
      // The article was removed or hidden between being favourited and being picked up.
      LOG.info("Skipping auto-summary, article unavailable: articleId={}, reason={}",
          event.articleId(), ex.getStatusCode());
      meterRegistry.counter("article.summary.auto", "result", "UNAVAILABLE").increment();
    } catch (RuntimeException ex) {
      OperationOutcome.failed(ex);
      // Swallowed deliberately. The summary is a nice-to-have on top of favouriting; a
      // failure here must not poison the consumer or retry a paid call in a hot loop. The
      // reader can still generate it by hand from the drawer.
      LOG.warn("Auto-summary failed: articleId={}", event.articleId(), ex);
      meterRegistry.counter("article.summary.auto", "result", "ERROR").increment();
    }
  }
}
