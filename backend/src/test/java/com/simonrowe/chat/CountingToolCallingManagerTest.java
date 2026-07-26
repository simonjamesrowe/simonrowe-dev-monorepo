package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

class CountingToolCallingManagerTest {

  private final ToolCallingManager delegate = mock(ToolCallingManager.class);
  private final ToolCallCounter counter = new ToolCallCounter();
  private final CountingToolCallingManager manager =
      new CountingToolCallingManager(delegate, counter);

  private static ChatResponse responseWithToolCalls(final int toolCallCount) {
    List<AssistantMessage.ToolCall> toolCalls = java.util.stream.IntStream.range(0, toolCallCount)
        .mapToObj(i -> new AssistantMessage.ToolCall("id-" + i, "function", "getJobs", "{}"))
        .toList();
    return ChatResponse.builder()
        .generations(List.of(new Generation(
            AssistantMessage.builder()
                .content("")
                .properties(Map.of())
                .toolCalls(toolCalls)
                .build())))
        .build();
  }

  private static Prompt promptWithSession(final String sessionId) {
    ToolCallingChatOptions options = ToolCallingChatOptions.builder()
        .toolContext(sessionId == null ? Map.of() : Map.of("sessionId", sessionId))
        .build();
    return new Prompt("Jobs?", options);
  }

  @Test
  void countsToolCallsAndDelegatesUnchanged() {
    Prompt prompt = promptWithSession("s1");
    ChatResponse response = responseWithToolCalls(2);
    ToolExecutionResult expected = mock(ToolExecutionResult.class);
    when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

    ToolExecutionResult actual = manager.executeToolCalls(prompt, response);

    assertThat(actual).isSameAs(expected);
    assertThat(counter.takeCount("s1")).isEqualTo(2);
    verify(delegate).executeToolCalls(prompt, response);
    verifyNoMoreInteractions(delegate);
  }

  @Test
  void accumulatesAcrossSuccessiveRounds() {
    Prompt prompt = promptWithSession("s1");
    when(delegate.executeToolCalls(any(), any())).thenReturn(mock(ToolExecutionResult.class));

    manager.executeToolCalls(prompt, responseWithToolCalls(2));
    manager.executeToolCalls(prompt, responseWithToolCalls(1));

    assertThat(counter.takeCount("s1")).isEqualTo(3);
  }

  @Test
  void passesThroughTheDelegatesReturnValueUnchanged() {
    Prompt prompt = promptWithSession("s1");
    ToolExecutionResult expected = mock(ToolExecutionResult.class);
    when(delegate.executeToolCalls(any(), any())).thenReturn(expected);

    ToolExecutionResult actual = manager.executeToolCalls(prompt, responseWithToolCalls(1));

    assertThat(actual).isSameAs(expected);
  }

  @Test
  void countsNothingWhenToolContextHasNoSessionId() {
    Prompt prompt = promptWithSession(null);
    ChatResponse response = responseWithToolCalls(2);
    when(delegate.executeToolCalls(prompt, response)).thenReturn(mock(ToolExecutionResult.class));

    manager.executeToolCalls(prompt, response);

    assertThat(counter.takeCount("s1")).isZero();
    verify(delegate).executeToolCalls(prompt, response);
  }

  @Test
  void countsNothingWhenTheResponseHasNoToolCalls() {
    Prompt prompt = promptWithSession("s1");
    ChatResponse response = ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage("plain answer"))))
        .build();
    when(delegate.executeToolCalls(prompt, response)).thenReturn(mock(ToolExecutionResult.class));

    manager.executeToolCalls(prompt, response);

    assertThat(counter.takeCount("s1")).isZero();
    verify(delegate).executeToolCalls(prompt, response);
  }

  @Test
  void stillDelegatesAndSwallowsWhenCountingLogicThrows() {
    Prompt prompt = new Prompt("Jobs?", (ChatOptions) null);
    ChatResponse response = responseWithToolCalls(1);
    ToolExecutionResult expected = mock(ToolExecutionResult.class);
    when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

    ToolExecutionResult[] actual = new ToolExecutionResult[1];
    assertThatCode(() -> actual[0] = manager.executeToolCalls(prompt, response))
        .doesNotThrowAnyException();

    assertThat(actual[0]).isSameAs(expected);
    verify(delegate).executeToolCalls(prompt, response);
  }
}
