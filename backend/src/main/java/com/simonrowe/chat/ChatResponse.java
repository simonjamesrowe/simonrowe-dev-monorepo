package com.simonrowe.chat;

import java.time.Instant;

public record ChatResponse(
    String sessionId,
    String content,
    MessageType type,
    String timestamp,
    String toolLabel,
    String widgetKind,
    Object payload
) {

  public enum MessageType {
    STREAM_START,
    STREAM_CHUNK,
    STREAM_END,
    TOOL_START,
    TOOL_END,
    WIDGET,
    ERROR
  }

  public static ChatResponse streamStart(String sessionId) {
    return new ChatResponse(sessionId, "", MessageType.STREAM_START,
        Instant.now().toString(), null, null, null);
  }

  public static ChatResponse streamChunk(String sessionId, String content) {
    return new ChatResponse(sessionId, content, MessageType.STREAM_CHUNK,
        Instant.now().toString(), null, null, null);
  }

  public static ChatResponse streamEnd(String sessionId, String content) {
    return new ChatResponse(sessionId, content, MessageType.STREAM_END,
        Instant.now().toString(), null, null, null);
  }

  public static ChatResponse toolStart(String sessionId, String label) {
    return new ChatResponse(sessionId, "", MessageType.TOOL_START,
        Instant.now().toString(), label, null, null);
  }

  public static ChatResponse toolEnd(String sessionId, String label) {
    return new ChatResponse(sessionId, "", MessageType.TOOL_END,
        Instant.now().toString(), label, null, null);
  }

  public static ChatResponse widget(String sessionId, String kind, Object payload) {
    return new ChatResponse(sessionId, "", MessageType.WIDGET,
        Instant.now().toString(), null, kind, payload);
  }

  public static ChatResponse error(String sessionId, String message) {
    return new ChatResponse(sessionId, message, MessageType.ERROR,
        Instant.now().toString(), null, null, null);
  }
}
