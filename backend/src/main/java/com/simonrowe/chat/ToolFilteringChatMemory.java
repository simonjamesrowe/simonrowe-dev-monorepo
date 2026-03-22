package com.simonrowe.chat;

import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * A ChatMemory decorator that filters out tool-related messages before storing them.
 * This prevents issues with providers (e.g. Groq) that cannot replay tool call/response
 * messages in conversation history.
 */
public class ToolFilteringChatMemory implements ChatMemory {

  private final ChatMemory delegate;

  public ToolFilteringChatMemory(final ChatMemory delegate) {
    this.delegate = delegate;
  }

  @Override
  public void add(final String conversationId, final Message message) {
    if (!isToolMessage(message)) {
      delegate.add(conversationId, message);
    }
  }

  @Override
  public void add(final String conversationId, final List<Message> messages) {
    List<Message> filtered = messages.stream()
        .filter(msg -> !isToolMessage(msg))
        .toList();
    if (!filtered.isEmpty()) {
      delegate.add(conversationId, filtered);
    }
  }

  @Override
  public List<Message> get(final String conversationId) {
    return delegate.get(conversationId);
  }

  @Override
  public void clear(final String conversationId) {
    delegate.clear(conversationId);
  }

  private boolean isToolMessage(final Message message) {
    if (message instanceof ToolResponseMessage) {
      return true;
    }
    if (message instanceof AssistantMessage assistantMessage) {
      return assistantMessage.hasToolCalls();
    }
    return false;
  }
}
