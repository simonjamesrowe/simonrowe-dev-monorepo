package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

  @Test
  void handleChatMessageSendsStreamStartBeforeContent() {
    final String sessionId = "session-1";
    given(chatService.processMessage(eq(sessionId), any()))
        .willReturn(Flux.just("Hello"));

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
        .willReturn(Flux.just("Hello", " World"));

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
        .willReturn(Flux.just("Many skills"));

    chatController.handleChatMessage(
        new ChatRequest(sessionId, message));

    verify(chatService).processMessage(sessionId, message);
  }
}
