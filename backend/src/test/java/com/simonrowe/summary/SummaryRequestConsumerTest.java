package com.simonrowe.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.events.SummaryRequestEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SummaryRequestConsumerTest {

  @Mock private ArticleSummaryService summaryService;

  private SimpleMeterRegistry metrics;
  private SummaryRequestConsumer consumer;

  @BeforeEach
  void setUp() {
    metrics = new SimpleMeterRegistry();
    consumer = new SummaryRequestConsumer(summaryService, metrics);
  }

  private static SummaryRequestEvent event() {
    return new SummaryRequestEvent("art-1", Instant.now());
  }

  private static ArticleSummaryService.RequestResult result(
      final ArticleSummaryResponse.PublicState state) {
    return new ArticleSummaryService.RequestResult(
        new ArticleSummaryResponse(state, 2, null, null, null, null, false, "msg"),
        false);
  }

  @Test
  void generatesTheSummaryForTheArticle() {
    when(summaryService.request("art-1"))
        .thenReturn(result(ArticleSummaryResponse.PublicState.READY));

    consumer.handle(event());

    verify(summaryService).request("art-1");
    assertThat(metrics.counter("article.summary.auto", "result", "READY").count())
        .isEqualTo(1);
  }

  /**
   * An article removed or hidden between being favourited and being picked up is not an
   * error worth retrying.
   */
  @Test
  void unavailableArticleIsRecordedAndSwallowed() {
    when(summaryService.request("art-1")).thenThrow(
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found"));

    assertThatCode(() -> consumer.handle(event())).doesNotThrowAnyException();

    assertThat(metrics.counter("article.summary.auto", "result", "UNAVAILABLE").count())
        .isEqualTo(1);
  }

  /**
   * A failure must not poison the consumer or retry a paid model call in a hot loop — the
   * reader can still generate the summary by hand from the drawer.
   */
  @Test
  void unexpectedFailureIsRecordedAndSwallowed() {
    when(summaryService.request("art-1"))
        .thenThrow(new IllegalStateException("model exploded"));

    assertThatCode(() -> consumer.handle(event())).doesNotThrowAnyException();

    assertThat(metrics.counter("article.summary.auto", "result", "ERROR").count())
        .isEqualTo(1);
  }

  /**
   * A stored non-retryable failure comes back as FAILED without a model call, so a
   * redelivered event is cheap. Roughly a third of the aggregated sources are RSS-only and
   * fail INSUFFICIENT_SOURCE_TEXT permanently.
   */
  @Test
  void storedFailureIsRecordedWithoutSpecialHandling() {
    when(summaryService.request("art-1"))
        .thenReturn(result(ArticleSummaryResponse.PublicState.FAILED));

    consumer.handle(event());

    assertThat(metrics.counter("article.summary.auto", "result", "FAILED").count())
        .isEqualTo(1);
  }
}
