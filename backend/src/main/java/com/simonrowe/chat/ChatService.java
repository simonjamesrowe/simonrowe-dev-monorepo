package com.simonrowe.chat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
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

    // Two complementary session mechanisms, deliberately kept together after merging #81.
    //
    // 1. ChatTurnTracer's chat-turn span carries session.id AND the trace name and trace-level
    //    input/output. Langfuse applies those to the trace via hasTraceUpdates() even though the
    //    span is not the trace root, which is what stops traces arriving "shallow".
    // 2. The langfuse.session.id BAGGAGE entry below is copied onto every span created while it
    //    is current, by the OTEL_JAVA_EXPERIMENTAL_SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE SDK
    //    setting (see docker-compose.prod.yml). Unlike (1) it also reaches Spring AI's tool-call,
    //    embedding and vector-store spans, which lose the observation context across
    //    Schedulers.boundedElastic and start their own traces — so those otherwise sessionless
    //    traces still group under the right Session.
    //
    // Both write the same value, and langfuse.session.id takes precedence over session.id in
    // Langfuse's own resolution order, so there is no conflict.
    try (Scope scope = Baggage.current().toBuilder()
        .put("langfuse.session.id", sessionId)
        .build()
        .makeCurrent()) {
      return turnTracer.trace(sessionId, message, () -> chatClient.prompt()
          .user(message)
          .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
          .toolContext(Map.of("sessionId", sessionId))
          .stream()
          .chatResponse());
    }
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
