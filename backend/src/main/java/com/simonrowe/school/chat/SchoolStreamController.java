package com.simonrowe.school.chat;

import com.simonrowe.chat.ChatResponse;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Streams school chat over STOMP, so Term Time behaves like the rest of the site.
 *
 * <p>Publishes the <b>same {@link ChatResponse} wire type</b> as the portfolio chat, on
 * {@code /topic/school.<sessionId>}. That is the whole reason the frontend can reuse
 * `chatStreamReducer`, `ChatMessage` and `ToolActivityBlock` unchanged — a bespoke frame shape
 * here would have meant a second reducer, and two reducers drift.
 *
 * <p>A separate destination rather than sharing {@code chat.send}: the two assistants have
 * different corpora, different guardrails and different tiering, and multiplexing them on one
 * destination would make the audience check depend on a field in the payload.
 */
@Controller
public class SchoolStreamController {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolStreamController.class);

  /** Matches the portfolio chat's per-session cap; the same abuse surface, the same answer. */
  private static final int MAX_MESSAGES_PER_SESSION = 20;

  private final SchoolChatService chatService;
  private final SchoolAudienceResolver audienceResolver;
  private final SchoolProperties properties;
  private final SimpMessagingTemplate messagingTemplate;
  private final SchoolStreamPublisher streamPublisher;
  private final ConcurrentHashMap<String, AtomicInteger> sessionMessageCounts =
      new ConcurrentHashMap<>();

  public SchoolStreamController(
      final SchoolChatService chatService,
      final SchoolAudienceResolver audienceResolver,
      final SchoolProperties properties,
      final SimpMessagingTemplate messagingTemplate,
      final SchoolStreamPublisher streamPublisher) {
    this.chatService = chatService;
    this.audienceResolver = audienceResolver;
    this.properties = properties;
    this.messagingTemplate = messagingTemplate;
    this.streamPublisher = streamPublisher;
  }

  /**
   * Handles one turn.
   *
   * @param request the question, year group and optional token
   */
  @MessageMapping("school.send")
  public void handle(final SchoolChatRequest request) {
    final String sessionId = request.sessionId();
    final String destination = "/topic/school." + sessionId;

    if (!properties.enabled()) {
      messagingTemplate.convertAndSend(destination,
          ChatResponse.error(sessionId, "Term Time is not switched on."));
      return;
    }

    final int count = sessionMessageCounts
        .computeIfAbsent(sessionId, key -> new AtomicInteger(0))
        .incrementAndGet();
    if (count > MAX_MESSAGES_PER_SESSION) {
      // The session is over, so drop its history now rather than waiting for the memory's TTL.
      // The browser mints a fresh session id when the visitor starts a new chat, so nothing
      // will ever ask for this one again.
      chatService.forget(sessionId);
      messagingTemplate.convertAndSend(destination, ChatResponse.error(sessionId,
          "Message limit reached for this session. Please start a new chat."));
      return;
    }

    // Resolved from the token alone, never from anything else in the payload.
    final SchoolAudience audience = audienceResolver.resolve(request.accessToken());
    final java.util.List<String> yearGroups = YearGroups.sanitise(request.yearGroups());

    messagingTemplate.convertAndSend(destination, ChatResponse.streamStart(sessionId));

    final StringBuilder assembled = new StringBuilder();
    chatService.stream(request.message(), yearGroups, audience, sessionId, streamPublisher,
            null)
        .doOnNext(chunk -> {
          if (chunk == null || chunk.isEmpty()) {
            return;
          }
          assembled.append(chunk);
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamChunk(sessionId, chunk));
        })
        .doOnComplete(() -> {
          final String vetted = assembled.toString();
          // When the name check rewrites the answer the browser has already rendered the
          // streamed chunks, so the terminal frame carries the replacement text and the
          // frontend replaces rather than appends on STREAM_END. Streaming a withheld answer
          // and then quietly correcting it afterwards would be worse than not streaming.
          messagingTemplate.convertAndSend(destination,
              ChatResponse.streamEnd(sessionId, vetted));
        })
        .doOnError(error -> {
          LOG.warn("School chat stream failed for session {}: {}",
              sessionId, error.getMessage());
          messagingTemplate.convertAndSend(destination, ChatResponse.error(sessionId,
              "Sorry, I'm having trouble responding right now. Please try again."));
        })
        .subscribe();
  }

  ConcurrentHashMap<String, AtomicInteger> getSessionMessageCounts() {
    return sessionMessageCounts;
  }
}
