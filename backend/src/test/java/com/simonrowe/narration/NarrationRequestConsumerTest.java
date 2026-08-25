package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.events.NarrationRequestEvent;
import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NarrationRequestConsumerTest {

  @Mock private NarrationService service;
  @Mock private NarrationRepository repository;
  @Mock private NarrationProvider provider;
  @Mock private NarrationStorage storage;
  @Mock private NarrationBudgetService budget;
  @Mock private NarrationRequestPublisher publisher;

  private SimpleMeterRegistry metrics;
  private NarrationRequestConsumer consumer;

  @BeforeEach
  void setUp() {
    metrics = new SimpleMeterRegistry();
    consumer = new NarrationRequestConsumer(service, repository,
        provider, storage, budget, NarrationBudgetServiceTest.properties(1_000_000),
        publisher, metrics);
  }

  @Test
  void generatesOnceAndMarksReadyAfterRevalidation() {
    Narration narration = processing("narration-1");
    Blog blog = blog();
    when(service.claim(eq("narration-1"), any())).thenReturn(Optional.of(narration));
    when(service.descriptor(NarrationContentType.BLOG, "blog-1")).thenReturn(
        new NarrationSource.NarrationDescriptor("narration-1", "Speech"));
    when(provider.isConfigured()).thenReturn(true);
    when(budget.allows(any(), any())).thenReturn(true);
    when(provider.start("Speech", "narrations/narration-1.mp3"))
        .thenReturn(new NarrationProvider.StartResult("operations/1"));
    when(provider.poll("operations/1"))
        .thenReturn(NarrationProvider.OperationResult.success());
    when(provider.download("narrations/narration-1.mp3"))
        .thenReturn(new byte[]{'I', 'D', '3', 1});
    when(service.isCurrentAndPublished(narration)).thenReturn(true);
    when(storage.store(any(), any())).thenReturn(new NarrationStorage.StoredNarration(
        "/uploads/narrations/narration-1/narration.mp3", 4, "checksum", 1));

    consumer.handle(event("narration-1"));

    assertThat(narration.status()).isEqualTo(NarrationStatus.READY);
    verify(provider).start("Speech", "narrations/narration-1.mp3");
    assertThat(metrics.counter("narration.jobs", "result", "ready").count())
        .isEqualTo(1);
  }

  @Test
  void resumesKnownOperationWithoutSubmittingAgain() {
    Narration narration = processing("narration-1");
    narration.markProviderRequestStarted(Instant.now());
    narration.markProviderOperation("operations/known", Instant.now());
    stubReadyPath(narration);
    when(provider.poll("operations/known"))
        .thenReturn(NarrationProvider.OperationResult.success());

    consumer.handle(event("narration-1"));

    verify(provider, never()).start(any(), any());
    assertThat(narration.status()).isEqualTo(NarrationStatus.READY);
  }

  @Test
  void ambiguousStartIsNonRetryableAndNeverRepublished() {
    Narration narration = processing("narration-1");
    Blog blog = blog();
    when(service.claim(eq("narration-1"), any())).thenReturn(Optional.of(narration));
    when(service.descriptor(NarrationContentType.BLOG, "blog-1")).thenReturn(
        new NarrationSource.NarrationDescriptor("narration-1", "Speech"));
    when(provider.isConfigured()).thenReturn(true);
    when(budget.allows(any(), any())).thenReturn(true);
    when(provider.start(any(), any())).thenThrow(new NarrationProviderException(
        "timeout", FailureKind.AMBIGUOUS, null));

    consumer.handle(event("narration-1"));

    assertThat(narration.status()).isEqualTo(NarrationStatus.UNCERTAIN);
    assertThat(narration.retryable()).isFalse();
    verify(publisher, never()).publish(anyString());
  }

  @Test
  void redeliveryAfterClaimWasConsumedDoesNothing() {
    when(service.claim(eq("narration-1"), any())).thenReturn(Optional.empty());

    consumer.handle(event("narration-1"));

    verify(provider, never()).start(any(), any());
    verify(repository, never()).save(any(Narration.class));
  }

  private void stubReadyPath(final Narration narration) {
    Blog blog = blog();
    when(service.claim(eq("narration-1"), any())).thenReturn(Optional.of(narration));
    when(service.descriptor(NarrationContentType.BLOG, "blog-1")).thenReturn(
        new NarrationSource.NarrationDescriptor("narration-1", "Speech"));
    when(provider.download("narrations/narration-1.mp3"))
        .thenReturn(new byte[]{'I', 'D', '3', 1});
    when(service.isCurrentAndPublished(narration)).thenReturn(true);
    when(storage.store(any(), any())).thenReturn(new NarrationStorage.StoredNarration(
        "/uploads/narrations/narration-1/narration.mp3", 4, "checksum", 1));
  }

  private static Narration processing(final String id) {
    Narration narration = new Narration(
        id, NarrationContentType.BLOG, "blog-1", 100, "voice", "en-GB", "MP3",
        "narrations/" + id + ".mp3", Instant.now());
    narration.claimed(Instant.now().plusSeconds(60), Instant.now());
    return narration;
  }

  private static NarrationRequestEvent event(final String id) {
    return new NarrationRequestEvent(id, Instant.now());
  }

  private static Blog blog() {
    Instant now = Instant.now();
    return new Blog("blog-1", "Title", "Short", "Content", true,
        null, now, now, List.of(), List.of(), BlogContentType.ENGINEERING);
  }
}
