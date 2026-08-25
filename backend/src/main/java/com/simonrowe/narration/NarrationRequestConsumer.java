package com.simonrowe.narration;

import com.simonrowe.events.NarrationRequestEvent;
import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NarrationRequestConsumer {

  private static final Logger LOG =
      LoggerFactory.getLogger(NarrationRequestConsumer.class);

  private final NarrationService narrationService;
  private final NarrationRepository narrationRepository;
  private final NarrationProvider provider;
  private final NarrationStorage storage;
  private final NarrationBudgetService budgetService;
  private final NarrationProperties properties;
  private final NarrationRequestPublisher publisher;
  private final NarrationScriptChunker chunker;
  private final MeterRegistry meterRegistry;

  public NarrationRequestConsumer(
      final NarrationService narrationService,
      final NarrationRepository narrationRepository,
      final NarrationProvider provider,
      final NarrationStorage storage,
      final NarrationBudgetService budgetService,
      final NarrationProperties properties,
      final NarrationRequestPublisher publisher,
      final NarrationScriptChunker chunker,
      final MeterRegistry meterRegistry
  ) {
    this.narrationService = narrationService;
    this.narrationRepository = narrationRepository;
    this.provider = provider;
    this.storage = storage;
    this.budgetService = budgetService;
    this.properties = properties;
    this.publisher = publisher;
    this.chunker = chunker;
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
      NarrationSource.NarrationDescriptor descriptor;
      try {
        descriptor = narrationService.descriptor(
            narration.contentType(), narration.contentId());
      } catch (RuntimeException ex) {
        // The content is gone, unpublished, or no longer narratable — either way there is
        // nothing left to synthesise.
        markStale(narration);
        return;
      }
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

        // Short scripts go through the ordinary synthesis endpoint, which still returns
        // MP3 directly. Google's long-audio endpoint currently rejects MP3 outright, so
        // this is the only path that produces the format we store. Routing on script size
        // rather than content type means a short blog benefits too.
        List<String> chunks = synthesisChunks(descriptor.script());
        if (!chunks.isEmpty()) {
          byte[] audio;
          try {
            audio = synthesizeChunks(narration, chunks);
          } catch (NarrationProviderException ex) {
            handleProviderFailure(narration, ex, true);
            return;
          }
          meterRegistry.counter("narration.provider.characters")
              .increment(narration.scriptCharacterCount());
          store(narration, audio);
          return;
        }

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

  /**
   * The script split into synchronous-synthesis requests, or empty when the provider has no
   * synchronous path and the long-audio route must be used instead.
   */
  private List<String> synthesisChunks(final String script) {
    int limit = provider.maxImmediateBytes();
    if (limit <= 0) {
      return List.of();
    }
    return chunker.chunk(script, limit);
  }

  /**
   * Synthesises every chunk and joins the results into one MP3.
   *
   * <p>Plain byte concatenation is sound here: Google returns a bare MPEG frame stream with
   * no ID3 header, and every chunk is encoded with identical voice and audio settings, so
   * the frames simply continue.
   *
   * <p>The lease is extended between chunks. A long blog is a dozen sequential requests,
   * which can outlast the claim; without this the recovery scheduler would republish the
   * job and we would pay to synthesise the same script twice.
   */
  private byte[] synthesizeChunks(
      final Narration narration, final List<String> chunks) {
    if (chunks.size() > 1) {
      LOG.info("Synthesising narration in {} chunks: narrationId={}",
          chunks.size(), narration.id());
    }
    ByteArrayOutputStream combined = new ByteArrayOutputStream();
    for (int i = 0; i < chunks.size(); i++) {
      byte[] part = provider.synthesizeImmediately(chunks.get(i));
      combined.writeBytes(part);
      if (i < chunks.size() - 1) {
        narration.extendLease(
            Instant.now().plus(properties.leaseDuration()), Instant.now());
        narrationRepository.save(narration);
      }
    }
    return combined.toByteArray();
  }

  private void finish(final Narration narration) {
    byte[] audio;
    try {
      audio = provider.download(narration.providerOutputObject());
    } catch (NarrationProviderException ex) {
      handleProviderFailure(narration, ex, false);
      return;
    }
    store(narration, audio);
  }

  /** Validates, stores and marks ready, re-checking currency either side of the write. */
  private void store(final Narration narration, final byte[] audio) {
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
