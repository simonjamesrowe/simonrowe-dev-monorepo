package com.simonrowe.events;

import java.time.Instant;

public record ContentChangeEvent(
    EventType eventType,
    ContentType contentType,
    String contentId,
    Instant timestamp
) {

  public enum EventType {
    CREATED,
    UPDATED,
    DELETED
  }

  public enum ContentType {
    BLOG,
    JOB,
    SKILL
  }
}
