package com.simonrowe.summary;

import com.simonrowe.common.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells every open news page when a summary starts, finishes or fails.
 *
 * <p>Without it, a card only learned about a summary from the request that asked for it, and
 * only while that request's drawer stayed open. Closing the drawer aborted the request, so a
 * reader who pressed Summarise on four cards in a row saw four cards still offering
 * Summarise, and the same was true of summaries started from another tab, by another visitor,
 * or by hearting an article (which summarises it through Kafka, with no browser waiting at
 * all).
 *
 * <p>Best-effort: a broadcast that fails is logged, never thrown. It runs straight after the
 * summary is saved, and a closed socket must not turn a written summary into a failed
 * request. A page that missed an update re-reads the state when its socket reconnects.
 */
@Component
public class SummaryStatusBroadcaster {

  /** Public topic: summaries, and which articles have one, are already public. */
  public static final String TOPIC = "/topic/summaries";

  private static final Logger LOG = LoggerFactory.getLogger(SummaryStatusBroadcaster.class);

  private final SimpMessagingTemplate messagingTemplate;

  public SummaryStatusBroadcaster(final SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * Announces that a summary has entered a state.
   *
   * @param articleId the aggregated article id
   * @param state the state it has just entered
   */
  public void broadcast(final String articleId, final ArticleSummaryResponse.PublicState state) {
    try {
      messagingTemplate.convertAndSend(TOPIC, new SummaryStatusUpdate(articleId, state));
    } catch (RuntimeException ex) {
      LOG.warn("Could not broadcast summary state: articleId={}, state={}",
          LogSafe.value(articleId), state, ex);
    }
  }
}
