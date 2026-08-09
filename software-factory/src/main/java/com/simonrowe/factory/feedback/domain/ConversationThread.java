package com.simonrowe.factory.feedback.domain;

import java.util.List;

/** Conversation thread (resolved or open) with one or more comments. */
public record ConversationThread(boolean resolved, List<ConversationComment> comments) {

  public ConversationThread {
    comments = comments == null ? List.of() : List.copyOf(comments);
  }
}
