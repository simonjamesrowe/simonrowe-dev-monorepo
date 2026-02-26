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

  @Test
  void processMessageCallsChatClientWithCorrectSessionIdAndReturnsFlux() {
    final String sessionId = "session-abc";
    final String message = "Hello, who are you?";
    final Flux<String> expectedFlux = Flux.just("I", " am", " an", " AI", ".");

    given(chatClient.prompt()).willReturn(requestSpec);
    given(requestSpec.user(message)).willReturn(requestSpec);
    given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
    given(requestSpec.stream()).willReturn(streamResponseSpec);
    given(streamResponseSpec.content()).willReturn(expectedFlux);

    final Flux<String> result = chatService.processMessage(sessionId, message);

    final List<String> tokens = result.collectList().block();
    assertThat(tokens).containsExactly("I", " am", " an", " AI", ".");

    verify(chatClient).prompt();
    verify(requestSpec).user(message);
    verify(requestSpec).advisors(any(Consumer.class));
    verify(requestSpec).stream();
    verify(streamResponseSpec).content();
  }

  @Test
  void processMessageRecordsSessionActivityForSessionId() {
    final String sessionId = "session-xyz";
    final String message = "Tell me about yourself.";

    given(chatClient.prompt()).willReturn(requestSpec);
    given(requestSpec.user(message)).willReturn(requestSpec);
    given(requestSpec.advisors(any(Consumer.class))).willReturn(requestSpec);
    given(requestSpec.stream()).willReturn(streamResponseSpec);
    given(streamResponseSpec.content()).willReturn(Flux.empty());

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
    given(streamResponseSpec.content()).willReturn(Flux.empty());

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
