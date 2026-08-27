package com.simonrowe.summary;

import com.simonrowe.common.LogSafe;
import com.simonrowe.events.SummaryRequestEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class SummaryRequestPublisher {

  public static final String TOPIC = "summary-requests";
  private static final Logger LOG =
      LoggerFactory.getLogger(SummaryRequestPublisher.class);

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public SummaryRequestPublisher(final KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  /**
   * Requests a summary for an article.
   *
   * <p>Keyed by article id, so repeat requests for the same article stay in order on one
   * partition and the consumer's insert-first guard sees them sequentially.
   *
   * @param articleId the aggregated article id
   */
  public void publish(final String articleId) {
    kafkaTemplate.send(TOPIC, articleId,
        new SummaryRequestEvent(articleId, Instant.now()));
    LOG.info("Published summary request: articleId={}", LogSafe.value(articleId));
  }
}
