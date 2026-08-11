package com.simonrowe.narration;

import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.events.NarrationRequestEvent;
import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NarrationRequestConsumer {

  private static final Logger LOG =
      LoggerFactory.getLogger(NarrationRequestConsumer.class);

  private final BlogNarrationService narrationService;
  private final BlogRepository blogRepository;
  private final NarrationRepository narrationRepository;
  private final NarrationProvider provider;
  private final NarrationStorage storage;
  private final NarrationBudgetService budgetService;
  private final NarrationProperties properties;
  private final NarrationRequestPublisher publisher;
  private final MeterRegistry meterRegistry;

  public NarrationRequestConsumer(
      final BlogNarrationService narrationService,
      final BlogRepository blogRepository,
      final NarrationRepository narrationRepository,
      final NarrationProvider provider,
      final NarrationStorage storage,
      final NarrationBudgetService budgetService,
      final NarrationProperties properties,
      final NarrationRequestPublisher publisher,
      final MeterRegistry meterRegistry
  ) {
    this.narrationService = narrationService;
    this.blogRepository = blogRepository;
    this.narrationRepository = narrationRepository;
    this.provider = provider;
    this.storage = storage;
    this.budgetService = budgetService;
    this.properties = properties;
    this.publisher = publisher;
    this.meterRegistry = meterRegistry;
  }

  @KafkaListener(
      topics = NarrationRequestPublisher.TOPIC,
      groupId = "narration-generator",
      concurrency = "${narration.consumer-concurrency:1}"
  )
  public void handle(final NarrationRequestEvent event) {
    Optional<Narration> claimed = narrationService.claim(
        event.narrationId(), Instant.now());
    if (claimed.isEmpty()) {
      return;
    }
    Narration narration = claimed.get();
    try {
      Optional<Blog> blog = blogRepository.findByIdAndPublishedTrue(narration.blogId());
      if (blog.isEmpty()) {
        markStale(narration);
        return;
      }
      BlogNarrationService.NarrationDescriptor descriptor =
          narrationService.descriptor(blog.get());
      if (!descriptor.id().equals(narration.id())) {
        markStale(narration);
        return;
      }
      if (narration.providerOperationName() == null) {
        if (!provider.isConfigured()) {
          markFailed(narration, "PROVIDER_UNAVAILABLE", true);
          return;
        }
        if (!budgetService.allows(narration, Instant.now())) {
          markFailed(narration, "BUDGET_EXHAUSTED", false);
          return;
        }
        narration.markProviderRequestStarted(Instant.now());
        narrationRepository.save(narration);
        NarrationProvider.StartResult started;
        try {
          started = provider.start(descriptor.script(), narration.providerOutputObject());
        } catch (NarrationProviderException ex) {
          handleProviderFailure(narration, ex, true);
          return;
        }
        narration.markProviderOperation(started.operationName(), Instant.now());
        narrationRepository.save(narration);
        meterRegistry.counter("narration.provider.characters")
            .increment(narration.scriptCharacterCount());
      }
      awaitCompletion(narration);
    } catch (RuntimeException ex) {
      LOG.error("Narration processing failed: narrationId={}", narration.id(), ex);
      markFailed(narration, "INTERNAL_FAILURE", true);
    }
  }

  private void awaitCompletion(final Narration narration) {
    Instant deadline = Instant.now().plus(properties.operationTimeout());
    while (Instant.now().isBefore(deadline)) {
      NarrationProvider.OperationResult result;
      try {
        result = provider.poll(narration.providerOperationName());
      } catch (NarrationProviderException ex) {
        handleProviderFailure(narration, ex, false);
        return;
      }
      if (result.done()) {
        if (!result.succeeded()) {
          markFailed(narration,
              result.failureCode() == null ? "GOOGLE_OPERATION_FAILED"
                  : result.failureCode(),
              true);
          return;
        }
        finish(narration);
        return;
      }
      narration.extendLease(
          Instant.now().plus(properties.leaseDuration()), Instant.now());
      narrationRepository.save(narration);
      sleep(properties.pollInterval().toMillis());
    }
    narration.markQueued(Instant.now());
    narrationRepository.save(narration);
    publisher.publish(narration.id());
  }

  private void finish(final Narration narration) {
    byte[] audio;
    try {
      audio = provider.download(narration.providerOutputObject());
    } catch (NarrationProviderException ex) {
      handleProviderFailure(narration, ex, false);
      return;
    }
    if (!narrationService.isCurrentAndPublished(narration)) {
      markStale(narration);
      return;
    }
    try {
      NarrationStorage.StoredNarration stored = storage.store(narration.id(), audio);
      if (!narrationService.isCurrentAndPublished(narration)) {
        storage.delete(narration.id());
        markStale(narration);
        return;
      }
      narration.markReady(stored, Instant.now());
      narrationRepository.save(narration);
      meterRegistry.counter("narration.jobs", "result", "ready").increment();
      meterRegistry.timer("narration.generation.duration")
          .record(java.time.Duration.between(
              narration.startedAt(), narration.completedAt()));
      LOG.info("Narration ready: narrationId={}, characters={}, bytes={}",
          narration.id(), narration.scriptCharacterCount(), stored.fileSize());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      markFailed(narration, "INVALID_AUDIO", true);
    }
  }

  private void handleProviderFailure(
      final Narration narration,
      final NarrationProviderException failure,
      final boolean duringStart
  ) {
    if (failure.kind() == FailureKind.AMBIGUOUS && duringStart) {
      narration.markUncertain("PROVIDER_OUTCOME_UNCERTAIN", Instant.now());
      narrationRepository.save(narration);
      meterRegistry.counter("narration.jobs", "result", "uncertain").increment();
      return;
    }
    if (!duringStart && narration.providerOperationName() != null
        && failure.kind() == FailureKind.SAFE_TO_RETRY) {
      narration.markQueued(Instant.now());
      narrationRepository.save(narration);
      publisher.publish(narration.id());
      return;
    }
    markFailed(narration, failure.kind() == FailureKind.UNAVAILABLE
        ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED", true);
  }

  private void markFailed(
      final Narration narration,
      final String code,
      final boolean retryable
  ) {
    narration.markFailed(code, retryable, Instant.now());
    narrationRepository.save(narration);
    meterRegistry.counter("narration.jobs", "result", "failed").increment();
  }

  private void markStale(final Narration narration) {
    narration.markStale(Instant.now());
    narrationRepository.save(narration);
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
