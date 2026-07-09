package com.simonrowe.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class GuardrailAdvisorTest {

  @Test
  void testSafeRequest() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("SAFE")))));

    GuardrailAdvisor advisor = new GuardrailAdvisor(chatModel);

    ChatClientRequest request =
        new ChatClientRequest(
            new Prompt(new UserMessage("What languages do you know?")), new HashMap<>());

    CallAdvisorChain chain = mock(CallAdvisorChain.class);

    ChatClientResponse expectedResponse =
        new ChatClientResponse(
            new ChatResponse(List.of(new Generation(new AssistantMessage("Java")))),
            new HashMap<>());

    when(chain.nextCall(request)).thenReturn(expectedResponse);

    ChatClientResponse response = advisor.adviseCall(request, chain);
    assertEquals("Java", response.chatResponse().getResult().getOutput().getText());
    verify(chain, times(1)).nextCall(request);
  }

  @Test
  void testOffTopicRequest() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("OFF_TOPIC")))));

    GuardrailAdvisor advisor = new GuardrailAdvisor(chatModel);
    ChatClientRequest request =
        new ChatClientRequest(new Prompt(new UserMessage("What's the weather?")), new HashMap<>());

    CallAdvisorChain chain = mock(CallAdvisorChain.class);

    ChatClientResponse response = advisor.adviseCall(request, chain);
    String expectedMsg =
        "I'm Simon's portfolio assistant and can only answer questions "
            + "related to his professional experience.";
    assertEquals(expectedMsg, response.chatResponse().getResult().getOutput().getText());
    verify(chain, never()).nextCall(any());
  }
}
