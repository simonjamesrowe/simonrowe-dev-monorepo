package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

  @Mock
  private ChatService chatService;

  @Mock
  private SimpMessagingTemplate messagingTemplate;

  @InjectMocks
  private ChatController chatController;

  private static org.springframework.ai.chat.model.ChatResponse aiResponse(
      final String text) {
    return org.springframework.ai.chat.model.ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage(text))))
        .build();
  }

  @Test
  void handleChatMessageSendsStreamStartBeforeContent() {
    final String sessionId = "session-1";
    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.just(aiResponse("Hello")));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "Hi"));

    final ArgumentCaptor<ChatResponse> captor =
        ArgumentCaptor.forClass(ChatResponse.class);
    verify(messagingTemplate, times(3)).convertAndSend(
        eq("/topic/chat.session-1"), captor.capture());

    assertThat(captor.getAllValues().get(0).type())
        .isEqualTo(ChatResponse.MessageType.STREAM_START);
  }

  @Test
  void handleChatMessageSendsChunksAndStreamEnd() {
    final String sessionId = "session-2";
    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.just(aiResponse("Hello"), aiResponse(" World")));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "Greetings"));

    final ArgumentCaptor<ChatResponse> captor =
        ArgumentCaptor.forClass(ChatResponse.class);
    // 1 START + 2 CHUNK + 1 END = 4 messages
    verify(messagingTemplate, times(4)).convertAndSend(
        eq("/topic/chat.session-2"), captor.capture());

    assertThat(captor.getAllValues().get(1).type())
        .isEqualTo(ChatResponse.MessageType.STREAM_CHUNK);
    assertThat(captor.getAllValues().get(1).content())
        .isEqualTo("Hello");
    assertThat(captor.getAllValues().get(2).type())
        .isEqualTo(ChatResponse.MessageType.STREAM_CHUNK);
    assertThat(captor.getAllValues().get(2).content())
        .isEqualTo(" World");
    assertThat(captor.getAllValues().get(3).type())
        .isEqualTo(ChatResponse.MessageType.STREAM_END);
    assertThat(captor.getAllValues().get(3).content())
        .isEqualTo("Hello World");
  }

  @Test
  void handleChatMessageSendsErrorOnFluxError() {
    final String sessionId = "session-3";
    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.error(new RuntimeException("LLM unavailable")));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "Test"));

    final ArgumentCaptor<ChatResponse> captor =
        ArgumentCaptor.forClass(ChatResponse.class);
    // 1 START + 1 ERROR = 2 messages
    verify(messagingTemplate, times(2)).convertAndSend(
        eq("/topic/chat.session-3"), captor.capture());

    final ChatResponse errorResponse =
        captor.getAllValues().get(1);
    assertThat(errorResponse.type())
        .isEqualTo(ChatResponse.MessageType.ERROR);
    assertThat(errorResponse.content())
        .contains("trouble responding");
  }

  @Test
  void handleChatMessageSendsToCorrectDestination() {
    final String sessionId = "unique-session";
    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.empty());

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "Hello"));

    verify(messagingTemplate, times(2)).convertAndSend(
        eq("/topic/chat.unique-session"), any(ChatResponse.class));
  }

  @Test
  void handleChatMessagePassesMessageToChatService() {
    final String sessionId = "session-5";
    final String message = "What skills does Simon have?";
    given(chatService.processMessage(sessionId, message))
        .willReturn(Flux.just(aiResponse("Many skills")));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, message));

    verify(chatService).processMessage(sessionId, message);
  }

  @Test
  void handleChatMessageDoesNotEmitStreamResetForToolCalls() {
    final String sessionId = "session-tool";
    final AssistantMessage toolCallMessage = AssistantMessage.builder()
        .content("Let me search for that")
        .toolCalls(List.of(new AssistantMessage.ToolCall(
            "call-1", "function", "searchBlogs", "{\"query\":\"website\"}")))
        .build();
    final org.springframework.ai.chat.model.ChatResponse toolCallResponse =
        org.springframework.ai.chat.model.ChatResponse.builder()
            .generations(List.of(new Generation(toolCallMessage)))
            .build();

    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.just(
            aiResponse("Let me search"),
            toolCallResponse,
            aiResponse("Here are the results")));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "Tell me about the blog"));

    final ArgumentCaptor<ChatResponse> captor =
        ArgumentCaptor.forClass(ChatResponse.class);
    // 1 START + 1 CHUNK("Let me search") + 1 CHUNK("Here are...") + 1 END
    verify(messagingTemplate, times(4)).convertAndSend(
        eq("/topic/chat." + sessionId), captor.capture());

    final List<ChatResponse> sent = captor.getAllValues();
    assertThat(sent.get(0).type()).isEqualTo(ChatResponse.MessageType.STREAM_START);
    assertThat(sent.get(1).type()).isEqualTo(ChatResponse.MessageType.STREAM_CHUNK);
    assertThat(sent.get(1).content()).isEqualTo("Let me search");
    assertThat(sent.get(2).type()).isEqualTo(ChatResponse.MessageType.STREAM_CHUNK);
    assertThat(sent.get(2).content()).isEqualTo("Here are the results");
    assertThat(sent.get(3).type()).isEqualTo(ChatResponse.MessageType.STREAM_END);
    assertThat(sent.get(3).content()).isEqualTo("Let me searchHere are the results");
  }

  @Test
  void handleChatMessageRejectsWhenSessionExceedsMessageLimit() {
    final String sessionId = "session-limited";
    // Pre-fill counter to the limit
    chatController.getSessionMessageCounts()
        .put(sessionId, new AtomicInteger(10));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, "One more message"));

    // Should only send an error, not call the chat service
    verify(chatService, never()).processMessage(any(), any());

    final ArgumentCaptor<ChatResponse> captor =
        ArgumentCaptor.forClass(ChatResponse.class);
    verify(messagingTemplate).convertAndSend(
        eq("/topic/chat." + sessionId), captor.capture());

    assertThat(captor.getValue().type())
        .isEqualTo(ChatResponse.MessageType.ERROR);
    assertThat(captor.getValue().content())
        .contains("Message limit reached");
  }
}
