package com.simonrowe.chat;

import java.time.Instant;

public record ChatResponse(
    String sessionId,
    String content,
    MessageType type,
    String timestamp
) {

  public enum MessageType {
    STREAM_START,
    STREAM_CHUNK,
    STREAM_END,
    ERROR
  }

  public static ChatResponse streamStart(String sessionId) {
    return new ChatResponse(sessionId, "", MessageType.STREAM_START,
        Instant.now().toString());
  }

  public static ChatResponse streamChunk(String sessionId, String content) {
    return new ChatResponse(sessionId, content, MessageType.STREAM_CHUNK,
        Instant.now().toString());
  }

  public static ChatResponse streamEnd(String sessionId, String content) {
    return new ChatResponse(sessionId, content, MessageType.STREAM_END,
        Instant.now().toString());
  }

  public static ChatResponse error(String sessionId, String message) {
    return new ChatResponse(sessionId, message, MessageType.ERROR,
        Instant.now().toString());
  }
}
