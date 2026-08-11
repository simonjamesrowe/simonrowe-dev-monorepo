package com.simonrowe.narration;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NarrationRecoveryScheduler {

  private static final Logger LOG =
      LoggerFactory.getLogger(NarrationRecoveryScheduler.class);

  private final NarrationRepository narrationRepository;
  private final NarrationRequestPublisher publisher;
  private final NarrationProperties properties;

  public NarrationRecoveryScheduler(
      final NarrationRepository narrationRepository,
      final NarrationRequestPublisher publisher,
      final NarrationProperties properties
  ) {
    this.narrationRepository = narrationRepository;
    this.publisher = publisher;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${narration.recovery-delay:PT1M}",
      initialDelayString = "${narration.recovery-delay:PT1M}"
  )
  public void recover() {
    if (!properties.enabled()) {
      return;
    }
    Instant now = Instant.now();
    Instant queuedBefore = now.minus(properties.recoveryDelay());
    for (Narration narration : narrationRepository.findByStatusAndUpdatedAtBefore(
        NarrationStatus.QUEUED, queuedBefore)) {
      publisher.publish(narration.id());
    }
    for (Narration narration : narrationRepository.findByStatusAndLeaseUntilBefore(
        NarrationStatus.PROCESSING, now)) {
      if (narration.providerRequestStarted()
          && narration.providerOperationName() == null) {
        narration.markUncertain("PROVIDER_OUTCOME_UNCERTAIN", now);
        narrationRepository.save(narration);
        LOG.warn("Narration has ambiguous expired work: narrationId={}", narration.id());
      } else {
        narration.markQueued(now);
        narrationRepository.save(narration);
        publisher.publish(narration.id());
      }
    }
  }
}
