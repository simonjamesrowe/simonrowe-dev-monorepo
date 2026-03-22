package com.simonrowe.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

  private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);

  private final ChatService chatService;
  private final SimpMessagingTemplate messagingTemplate;

  public ChatController(final ChatService chatService,
      final SimpMessagingTemplate messagingTemplate) {
    this.chatService = chatService;
    this.messagingTemplate = messagingTemplate;
  }

  @MessageMapping("chat.send")
  public void handleChatMessage(final ChatRequest request) {
    String sessionId = request.sessionId();
    String destination = "/topic/chat." + sessionId;

    LOG.info("Received chat message for session: {}", sessionId);
    messagingTemplate.convertAndSend(destination,
        ChatResponse.streamStart(sessionId));

    StringBuilder fullResponse = new StringBuilder();

    chatService.processMessage(sessionId, request.message())
        .doOnNext(chunk -> {
          fullResponse.append(chunk);
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamChunk(sessionId, chunk));
        })
        .doOnComplete(() -> {
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamEnd(sessionId, fullResponse.toString()));
          LOG.info("Completed response for session: {}", sessionId);
        })
        .doOnError(error -> {
          LOG.error("Error processing chat for session: {}", sessionId, error);
          messagingTemplate.convertAndSend(destination,
              ChatResponse.error(sessionId,
                  "Sorry, I'm having trouble responding right now. Please try again."));
        })
        .subscribe();
  }
}
