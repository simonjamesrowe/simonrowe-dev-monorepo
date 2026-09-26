package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.simonrowe.chat.ToolFilteringChatMemory;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Term Time's conversation memory.
 *
 * <p>Term Time shipped with none: {@link SchoolChatService} takes the {@code ChatClient.Builder}
 * so it can set its own model and cache key, and so inherits none of the portfolio chat's default
 * advisors. The visible effect was an assistant that asked "which year group?" and then, on being
 * told, had no idea why it had asked.
 */
class SchoolChatMemoryTest {

  private static SchoolChatService serviceWith(final ChatMemory memory) {
    return new SchoolChatService(
        mock(ChatClient.Builder.class),
        mock(SchoolQueryService.class),
        mock(com.simonrowe.school.retrieval.SchoolRetrievalService.class),
        mock(SchoolTopicGuardrail.class),
        new SchoolProperties(
            true, null, List.of(), List.of(), null, null, null, 0, null, null, null, 0L,
            null, null),
        mock(SchoolBudget.class),
        mock(SchoolUsageRecorder.class),
        memory);
  }

  private static ChatClient.ChatClientRequestSpec mockRequest() {
    return mock(ChatClient.ChatClientRequestSpec.class,
        withSettings().defaultAnswer(org.mockito.Answers.RETURNS_SELF));
  }

  @Test
  @DisplayName("a turn with a session id gets conversation memory")
  void memoryIsAttachedForOneSession() {
    final SchoolChatService service = serviceWith(mock(ChatMemory.class));
    final ChatClient.ChatClientRequestSpec request = mockRequest();

    service.remembering(request, "school-abc123");

    verify(request).advisors(any(Advisor[].class));
  }

  @Test
  @DisplayName("a turn with no session id gets NO memory, rather than a shared default one")
  void noSessionIdMeansNoMemory() {
    // This is a safety property, not an optimisation. MessageChatMemoryAdvisor falls back to a
    // single default conversation id when none is supplied, so attaching it here would pool
    // every anonymous caller of POST /api/school/chat into one history they could all read.
    final SchoolChatService service = serviceWith(mock(ChatMemory.class));

    for (String sessionId : new String[] {null, "", "   "}) {
      final ChatClient.ChatClientRequestSpec request = mockRequest();
      assertThat(service.remembering(request, sessionId)).isSameAs(request);
      verify(request, never()).advisors(any(Advisor[].class));
    }
  }

  @Test
  @DisplayName("forget clears the conversation, and tolerates a missing id")
  void forgetClearsOneConversation() {
    final ChatMemory memory = mock(ChatMemory.class);
    final SchoolChatService service = serviceWith(memory);

    service.forget("school-abc123");
    service.forget(null);
    service.forget("  ");

    verify(memory).clear("school-abc123");
    verify(memory, never()).clear(null);
  }

  @Test
  @DisplayName("tool call and response messages are never stored in school memory")
  void toolMessagesAreFilteredOut() {
    // Load-bearing for Term Time specifically, because it is entirely tool-driven. A message
    // window truncates on count, so an unfiltered store will eventually cut an assistant
    // message carrying tool_calls away from its ToolResponseMessage — and OpenAI rejects a
    // conversation containing the one without the other. The failure would be a 400 that only
    // appears after enough turns to push the pair apart.
    final ChatMemory memory = new ToolFilteringChatMemory(
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new BoundedChatMemoryRepository(
                10, Duration.ofMinutes(30), Clock.systemUTC()))
            .maxMessages(20)
            .build());

    final List<org.springframework.ai.chat.messages.Message> turn = List.of(
        new UserMessage("what day is PE?"),
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(
                new AssistantMessage.ToolCall("1", "function", "searchSchoolDocuments", "{}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(List.of(
                new ToolResponseMessage.ToolResponse("1", "searchSchoolDocuments", "[]")))
            .build(),
        new AssistantMessage("Which year group?"));
    memory.add("a", turn);

    assertThat(memory.get("a"))
        .extracting(m -> m.getClass().getSimpleName())
        .containsExactly("UserMessage", "AssistantMessage");
    assertThat(memory.get("a").get(1).getText()).isEqualTo("Which year group?");
  }

  @Test
  @DisplayName("school memory forgets the oldest conversation rather than growing without limit")
  void schoolMemoryIsBounded() {
    // The portfolio chat's ChatMemory is unbounded and relies on a scheduled sweeper. That is
    // fine there and is not fine on an endpoint with no authentication, where the conversation
    // id is whatever the browser puts in the frame.
    final BoundedChatMemoryRepository store =
        new BoundedChatMemoryRepository(2, Duration.ofMinutes(30), Clock.system(ZoneOffset.UTC));
    final ChatMemory memory = new ToolFilteringChatMemory(
        MessageWindowChatMemory.builder().chatMemoryRepository(store).maxMessages(20).build());

    for (int i = 0; i < 50; i++) {
      memory.add("session-" + i, new UserMessage("q"));
    }

    assertThat(store.size()).isEqualTo(2);
  }
}
