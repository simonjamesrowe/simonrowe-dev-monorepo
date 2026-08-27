package com.simonrowe.events;

import com.simonrowe.common.LogSafe;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangeEvent.EventType;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentChangePublisher {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContentChangePublisher.class);
  private static final String TOPIC = "content-changes";

  private final KafkaTemplate<String, ContentChangeEvent> kafkaTemplate;

  public ContentChangePublisher(
      final KafkaTemplate<String, ContentChangeEvent> kafkaTemplate
  ) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishCreated(final ContentType contentType, final String contentId) {
    publish(EventType.CREATED, contentType, contentId);
  }

  public void publishUpdated(final ContentType contentType, final String contentId) {
    publish(EventType.UPDATED, contentType, contentId);
  }

  public void publishDeleted(final ContentType contentType, final String contentId) {
    publish(EventType.DELETED, contentType, contentId);
  }

  private void publish(final EventType eventType, final ContentType contentType,
      final String contentId) {
    ContentChangeEvent event = new ContentChangeEvent(
        eventType, contentType, contentId, Instant.now());
    kafkaTemplate.send(TOPIC, contentId, event);
    LOG.info("Published content change: {} {} {}", eventType, contentType,
        LogSafe.value(contentId));
  }
}
