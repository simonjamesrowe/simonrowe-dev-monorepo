package com.simonrowe.chat;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decorates the autoconfigured {@link ToolCallingManager} to count tool calls at the point they
 * are actually executed, because the model output can never be used for this.
 *
 * <p>When tool execution is required, {@code OpenAiSdkChatModel} never emits the aggregated
 * tool-call {@link ChatResponse} to its subscriber: it is consumed internally and replaced by a
 * recursive call into the model with the tool results appended to the conversation. So a
 * {@code ChatResponse} with {@code hasToolCalls() == true} never reaches {@code ChatService}, and
 * counting tool calls from the streamed model output is unreachable dead code. {@link
 * ToolCallingManager#executeToolCalls(Prompt, ChatResponse)} is the one place upstream that
 * definitely sees every tool-execution round, including parallel calls within a round, so this
 * decorator counts there instead and hands the count off to {@link ToolCallCounter} for {@link
 * ChatTurnTracer} to read at the end of the turn.
 */
public class CountingToolCallingManager implements ToolCallingManager {

  private static final Logger LOG = LoggerFactory.getLogger(CountingToolCallingManager.class);

  private static final String SESSION_ID_KEY = "sessionId";

  private final ToolCallingManager delegate;
  private final ToolCallCounter counter;

  public CountingToolCallingManager(final ToolCallingManager delegate,
      final ToolCallCounter counter) {
    this.delegate = delegate;
    this.counter = counter;
  }

  @Override
  public List<ToolDefinition> resolveToolDefinitions(final ToolCallingChatOptions chatOptions) {
    return delegate.resolveToolDefinitions(chatOptions);
  }

  @Override
  public ToolExecutionResult executeToolCalls(final Prompt prompt,
      final ChatResponse chatResponse) {
    countQuietly(prompt, chatResponse);
    return delegate.executeToolCalls(prompt, chatResponse);
  }

  /**
   * Counts the tool calls in {@code chatResponse} against the session named in the prompt's tool
   * context. Wrapped in a broad catch because tool execution must proceed even if this telemetry
   * bookkeeping fails for any reason.
   */
  private void countQuietly(final Prompt prompt, final ChatResponse chatResponse) {
    try {
      String sessionId = sessionIdOf(prompt);
      int size = toolCallCountOf(chatResponse);
      if (size > 0) {
        counter.increment(sessionId, size);
      }
    } catch (Exception e) {
      LOG.warn("Failed to count tool calls for a chat turn", e);
    }
  }

  private static String sessionIdOf(final Prompt prompt) {
    if (prompt == null) {
      return null;
    }
    ChatOptions options = prompt.getOptions();
    if (!(options instanceof ToolCallingChatOptions toolCallingChatOptions)) {
      return null;
    }
    if (toolCallingChatOptions.getToolContext() == null) {
      return null;
    }
    Object sessionId = toolCallingChatOptions.getToolContext().get(SESSION_ID_KEY);
    return sessionId instanceof String sessionIdString ? sessionIdString : null;
  }

  private static int toolCallCountOf(final ChatResponse chatResponse) {
    if (chatResponse == null || chatResponse.getResult() == null) {
      return 0;
    }
    AssistantMessage output = chatResponse.getResult().getOutput();
    if (output == null || output.getToolCalls() == null) {
      return 0;
    }
    return output.getToolCalls().size();
  }
}
