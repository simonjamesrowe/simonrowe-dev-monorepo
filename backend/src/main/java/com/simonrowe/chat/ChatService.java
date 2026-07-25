package com.simonrowe.chat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.WithSpan;
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
  private final ConcurrentHashMap<String, Instant> sessionActivity =
      new ConcurrentHashMap<>();

  public ChatService(final ChatClient chatClient, final ChatMemory chatMemory) {
    this.chatClient = chatClient;
    this.chatMemory = chatMemory;
  }

  @WithSpan
  public Flux<ChatResponse> processMessage(
      final String sessionId, final String message) {
    sessionActivity.put(sessionId, Instant.now());
    LOG.info("Processing message for session: {}", sessionId);

    // Baggage is captured into the reactive chain's context at assembly time (same mechanism
    // that already propagates the @WithSpan span across this Flux.defer boundary), then copied
    // onto every downstream span - including the gen_ai.* spans Spring AI creates - by the
    // OTEL_JAVA_EXPERIMENTAL_SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE SDK setting. This is what
    // lets Langfuse group chat traces into Sessions (see config/alloy/config.alloy's ai_only
    // filter, which drops every span except these, so the id must live on the gen_ai spans
    // themselves rather than on the (dropped) @WithSpan root span).
    try (Scope scope = Baggage.current().toBuilder()
        .put("langfuse.session.id", sessionId)
        .build()
        .makeCurrent()) {
      return Flux.defer(() -> chatClient.prompt()
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
