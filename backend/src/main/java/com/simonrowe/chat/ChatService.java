package com.simonrowe.chat;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

  private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

  private final ChatClient chatClient;
  private final ChatMemory chatMemory;
  private final ConcurrentHashMap<String, Instant> sessionActivity =
      new ConcurrentHashMap<>();

  public ChatService(final ChatClient chatClient, final ChatMemory chatMemory) {
    this.chatClient = chatClient;
    this.chatMemory = chatMemory;
  }

  @WithSpan
  public Flux<String> processMessage(final String sessionId, final String message) {
    sessionActivity.put(sessionId, Instant.now());
    LOG.info("Processing message for session: {}", sessionId);

    return chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .stream()
        .content();
  }

  public ConcurrentHashMap<String, Instant> getSessionActivity() {
    return sessionActivity;
  }

  public void evictSession(final String sessionId) {
    sessionActivity.remove(sessionId);
    chatMemory.clear(sessionId);
    LOG.info("Evicted chat session: {}", sessionId);
  }
}
