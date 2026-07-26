package com.simonrowe.chat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

  private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

  private final ChatClient chatClient;
  private final ChatMemory chatMemory;
  private final ChatTurnTracer turnTracer;
  private final ConcurrentHashMap<String, Instant> sessionActivity =
      new ConcurrentHashMap<>();

  public ChatService(final ChatClient chatClient, final ChatMemory chatMemory,
      final ChatTurnTracer turnTracer) {
    this.chatClient = chatClient;
    this.chatMemory = chatMemory;
    this.turnTracer = turnTracer;
  }

  public Flux<ChatResponse> processMessage(
      final String sessionId, final String message) {
    sessionActivity.put(sessionId, Instant.now());
    LOG.info("Processing message for session: {}", sessionId);

    // Session grouping comes from ChatTurnTracer's chat-turn span, which carries session.id
    // along with the trace name and trace-level input/output. Langfuse applies those to the
    // trace via hasTraceUpdates() even though the span is not the trace root.
    return turnTracer.trace(sessionId, message, () -> chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
        .toolContext(Map.of("sessionId", sessionId))
        .stream()
        .chatResponse());
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
