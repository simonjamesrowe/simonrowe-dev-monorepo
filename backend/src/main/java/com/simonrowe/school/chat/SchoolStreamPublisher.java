package com.simonrowe.school.chat;

import com.simonrowe.chat.ChatResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes tool activity frames to Term Time's topic.
 *
 * <p>Exists because {@code ChatStreamPublisher} hardcodes {@code /topic/chat.} as its
 * destination. Reusing it here sent every tool frame to the portfolio assistant's topic, where
 * nothing was subscribed — the answer still arrived, the activity lines simply never appeared,
 * and nothing anywhere reported a problem. Publishing to an unsubscribed STOMP topic is legal
 * and silent, which is what made it worth a separate class rather than a shared one with a
 * mutable prefix.
 *
 * <p>The frame type is the shared {@link ChatResponse}, so the browser's reducer handles these
 * identically to the portfolio chat's.
 */
@Component
public class SchoolStreamPublisher {

  private static final String DESTINATION_PREFIX = "/topic/school.";

  private final SimpMessagingTemplate messagingTemplate;

  public SchoolStreamPublisher(final SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * Signals that a tool has started.
   *
   * @param sessionId the session, which names the topic
   * @param label the human-readable activity line
   */
  public void toolStart(final String sessionId, final String label) {
    messagingTemplate.convertAndSend(
        DESTINATION_PREFIX + sessionId, ChatResponse.toolStart(sessionId, label));
  }

  /**
   * Signals that a tool has finished.
   *
   * @param sessionId the session, which names the topic
   * @param label the same label the start frame carried; the browser matches on it
   */
  public void toolEnd(final String sessionId, final String label) {
    messagingTemplate.convertAndSend(
        DESTINATION_PREFIX + sessionId, ChatResponse.toolEnd(sessionId, label));
  }
}
