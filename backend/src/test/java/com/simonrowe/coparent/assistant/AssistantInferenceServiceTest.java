package com.simonrowe.coparent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Family;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.web.server.ResponseStatusException;

class AssistantInferenceServiceTest {

  private final ChatModel chatModel = mock(ChatModel.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AssistantInferenceService service = new AssistantInferenceService(
      chatModel, properties(), meterRegistry);

  @Test
  @SuppressWarnings("unchecked")
  void definesCompleteStrictInertToolCatalog() throws Exception {
    assertThat(service.toolCallbacks()).hasSize(12);
    for (var callback : service.toolCallbacks()) {
      final String schemaJson = callback.getToolDefinition().inputSchema();
      assertThat(schemaJson).doesNotContain("%s");
      final Map<String, Object> schema = new tools.jackson.databind.ObjectMapper()
          .readValue(schemaJson, new tools.jackson.core.type.TypeReference<>() { });
      assertThat(schema.get("type")).isEqualTo("object");
      assertThat(schema.get("additionalProperties")).isEqualTo(false);
      final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      assertThat(new LinkedHashSet<>((List<String>) schema.get("required")))
          .containsExactlyInAnyOrderElementsOf(properties.keySet());
    }
    assertThatThrownBy(() -> service.toolCallbacks().getFirst().call("{}"))
        .isInstanceOf(ToolExecutionException.class);
  }

  @Test
  void returnsMultipleToolCallsWithPrivateStrictOptions() {
    when(chatModel.call(any(Prompt.class))).thenReturn(response(
        new AssistantMessage.ToolCall("one", "function", "propose_create_event",
            "{\"title\":\"School fair\"}"),
        new AssistantMessage.ToolCall("two", "function", "propose_send_message",
            "{\"message\":\"Bring the form\"}")));

    final List<AssistantInferenceService.ProposedCall> calls = service.propose(context(),
        new AssistantInputValidator.ValidatedInput("A note", null, null));

    assertThat(calls).extracting(AssistantInferenceService.ProposedCall::name)
        .containsExactly("propose_create_event", "propose_send_message");
    final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    final OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("gpt-5.4-nano");
    assertThat(options.getReasoningEffort()).isEqualTo("none");
    assertThat(options.getParallelToolCalls()).isTrue();
    assertThat(options.getStrict()).isTrue();
    assertThat(prompt.getValue().getInstructions())
        .allSatisfy(message -> assertThat(message.getMetadata())
            .containsKey("coparent.assistant.suppress-content"));
    assertThat(meterRegistry.get("coparent.assistant.proposed.actions").summary().totalAmount())
        .isEqualTo(2);
  }

  @Test
  void treatsNoToolRefusalAsNoActionAndRejectsMalformedArguments() {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage("I cannot help")))));
    assertThat(service.propose(context(),
        new AssistantInputValidator.ValidatedInput("Nothing", null, null))).isEmpty();

    when(chatModel.call(any(Prompt.class))).thenReturn(response(
        new AssistantMessage.ToolCall("bad", "function", "propose_create_event", "{")));
    assertThatThrownBy(() -> service.propose(context(),
        new AssistantInputValidator.ValidatedInput("Bad", null, null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsUnknownToolCalls() {
    when(chatModel.call(any(Prompt.class))).thenReturn(response(
        new AssistantMessage.ToolCall("unknown", "function", "delete_the_family", "{}")));

    assertThatThrownBy(() -> service.propose(context(),
        new AssistantInputValidator.ValidatedInput("Bad", null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("unknown action type");
  }

  private static ChatResponse response(final AssistantMessage.ToolCall... calls) {
    final AssistantMessage output = AssistantMessage.builder()
        .content("")
        .toolCalls(List.of(calls))
        .build();
    return new ChatResponse(List.of(new Generation(output)));
  }

  private static AssistantContextFactory.Context context() {
    final ObjectId familyId = new ObjectId();
    final Instant now = Instant.parse("2026-09-22T10:00:00Z");
    final Family family = new Family(familyId, "Home", "Europe/London", List.of(), List.of(),
        List.of(), null, now, now);
    return new AssistantContextFactory.Context("{\"timeZone\":\"Europe/London\"}", family, now);
  }

  private static CoparentProperties properties() {
    return new CoparentProperties(true, "coparent", "http://localhost", "email",
        "noreply@example.com", false, "legacy",
        new CoparentProperties.Assistant(true, "gpt-5.4-nano"));
  }
}
