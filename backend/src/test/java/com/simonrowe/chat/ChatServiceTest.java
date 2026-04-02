package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  @Mock
  private ChatClient chatClient;

  @Mock
  private ChatMemory chatMemory;

  @Mock
  private ChatClient.ChatClientRequestSpec requestSpec;

  @Mock
  private ChatClient.StreamResponseSpec streamResponseSpec;

  @InjectMocks
  private ChatService chatService;

  private static ChatResponse chatResponseWithText(final String text) {
    return ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage(text))))
        .build();
  }

  @Test
  void processMessageCallsChatClientWithCorrectSessionIdAndReturnsFlux() {
    final String sessionId = "session-abc";
    final String message = "Hello, who are you?";
    final Flux<ChatResponse> expectedFlux = Flux.just(
        chatResponseWithText("I"),
        chatResponseWithText(" am"),
        chatResponseWithText(" an"),
        chatResponseWithText(" AI"),
        chatResponseWithText("."));

    given(chatClient.prompt()).willReturn(requestSpec);
    given(requestSpec.user(message)).willReturn(requestSpec);
    given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
    given(requestSpec.stream()).willReturn(streamResponseSpec);
    given(streamResponseSpec.chatResponse()).willReturn(expectedFlux);

    final Flux<ChatResponse> result = chatService.processMessage(sessionId, message);

    final List<ChatResponse> responses = result.collectList().block();
    assertThat(responses).hasSize(5);
    assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("I");
    assertThat(responses.get(4).getResult().getOutput().getText()).isEqualTo(".");

    verify(chatClient).prompt();
    verify(requestSpec).user(message);
    verify(requestSpec).advisors(any(Consumer.class));
    verify(requestSpec).stream();
    verify(streamResponseSpec).chatResponse();
  }

  @Test
  void processMessageRecordsSessionActivityForSessionId() {
    final String sessionId = "session-xyz";
    final String message = "Tell me about yourself.";

    given(chatClient.prompt()).willReturn(requestSpec);
    given(requestSpec.user(message)).willReturn(requestSpec);
    given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
    given(requestSpec.stream()).willReturn(streamResponseSpec);
    given(streamResponseSpec.chatResponse()).willReturn(Flux.empty());

    final Instant before = Instant.now();
    chatService.processMessage(sessionId, message);
    final Instant after = Instant.now();

    final Instant recorded = chatService.getSessionActivity().get(sessionId);
    assertThat(recorded).isNotNull();
    assertThat(recorded).isAfterOrEqualTo(before);
    assertThat(recorded).isBeforeOrEqualTo(after);
  }

  @Test
  void evictSessionRemovesSessionFromActivityMap() {
    final String sessionId = "session-to-evict";
    final String message = "A message.";

    given(chatClient.prompt()).willReturn(requestSpec);
    given(requestSpec.user(message)).willReturn(requestSpec);
    given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
    given(requestSpec.stream()).willReturn(streamResponseSpec);
    given(streamResponseSpec.chatResponse()).willReturn(Flux.empty());

    chatService.processMessage(sessionId, message);
    assertThat(chatService.getSessionActivity()).containsKey(sessionId);

    chatService.evictSession(sessionId);

    assertThat(chatService.getSessionActivity()).doesNotContainKey(sessionId);
  }

  @Test
  void evictSessionClearsChatMemory() {
    final String sessionId = "session-memory-clear";

    chatService.evictSession(sessionId);

    verify(chatMemory).clear(sessionId);
  }

  @Test
  void evictSessionForNonExistentSessionIdDoesNotThrow() {
    chatService.evictSession("nonexistent-session");

    verify(chatMemory).clear("nonexistent-session");
    assertThat(chatService.getSessionActivity()).doesNotContainKey("nonexistent-session");
  }
}
