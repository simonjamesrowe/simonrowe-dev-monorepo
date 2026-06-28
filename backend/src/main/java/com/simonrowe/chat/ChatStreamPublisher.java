package com.simonrowe.chat;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatStreamPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  public ChatStreamPublisher(final SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  public void toolStart(final String sessionId, final String label) {
    publish(sessionId, ChatResponse.toolStart(sessionId, label));
  }

  public void toolEnd(final String sessionId, final String label) {
    publish(sessionId, ChatResponse.toolEnd(sessionId, label));
  }

  public void widget(final String sessionId, final String kind, final Object payload) {
    publish(sessionId, ChatResponse.widget(sessionId, kind, payload));
  }

  private void publish(final String sessionId, final ChatResponse response) {
    messagingTemplate.convertAndSend("/topic/chat." + sessionId, response);
  }
}
