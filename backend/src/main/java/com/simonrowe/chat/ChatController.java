package com.simonrowe.chat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

  private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
  private static final int MAX_MESSAGES_PER_SESSION = 10;

  private final ChatService chatService;
  private final SimpMessagingTemplate messagingTemplate;
  private final ConcurrentHashMap<String, AtomicInteger> sessionMessageCounts =
      new ConcurrentHashMap<>();

  public ChatController(final ChatService chatService,
      final SimpMessagingTemplate messagingTemplate) {
    this.chatService = chatService;
    this.messagingTemplate = messagingTemplate;
  }

  @MessageMapping("chat.send")
  public void handleChatMessage(final ChatRequest request) {
    String sessionId = request.sessionId();
    String destination = "/topic/chat." + sessionId;

    int count = sessionMessageCounts
        .computeIfAbsent(sessionId, key -> new AtomicInteger(0))
        .incrementAndGet();

    if (count > MAX_MESSAGES_PER_SESSION) {
      LOG.warn("Session {} exceeded message limit ({}/{})",
          sessionId, count, MAX_MESSAGES_PER_SESSION);
      messagingTemplate.convertAndSend(destination,
          ChatResponse.error(sessionId,
              "Message limit reached for this session. Please start a new chat."));
      return;
    }

    LOG.info("Received chat message for session: {} ({}/{})",
        sessionId, count, MAX_MESSAGES_PER_SESSION);
    messagingTemplate.convertAndSend(destination,
        ChatResponse.streamStart(sessionId));

    StringBuilder fullResponse = new StringBuilder();

    chatService.processMessage(sessionId, request.message())
        .doOnNext(aiResponse -> {
          if (aiResponse.hasToolCalls()) {
            LOG.debug("Tool call detected for session: {}", sessionId);
            return;
          }

          var result = aiResponse.getResult();
          if (result == null || result.getOutput() == null) {
            return;
          }
          String text = result.getOutput().getText();
          if (text == null || text.isEmpty()) {
            return;
          }

          fullResponse.append(text);
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamChunk(sessionId, text));
        })
        .doOnComplete(() -> {
          String content = fullResponse.toString();
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamEnd(sessionId, content));
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

  ConcurrentHashMap<String, AtomicInteger> getSessionMessageCounts() {
    return sessionMessageCounts;
  }
}
