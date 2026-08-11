package com.simonrowe.narration;

import com.simonrowe.events.NarrationRequestEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class NarrationRequestPublisher {

  public static final String TOPIC = "narration-requests";
  private static final Logger LOG =
      LoggerFactory.getLogger(NarrationRequestPublisher.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public NarrationRequestPublisher(
      final KafkaTemplate<String, Object> kafkaTemplate
  ) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publish(final String narrationId) {
    NarrationRequestEvent event = new NarrationRequestEvent(
        narrationId, Instant.now());
    kafkaTemplate.send(TOPIC, narrationId, event);
    LOG.info("Published narration request: narrationId={}", narrationId);
  }
}
