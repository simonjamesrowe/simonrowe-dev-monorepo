package com.simonrowe.coparent.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class StatementClassifierTest {

  private static final String ROBIN = "aaaaaaaaaaaaaaaaaaaaaaaa";

  private final ChatModel chatModel = mock(ChatModel.class);
  private final StatementClassifier classifier = new StatementClassifier(chatModel,
      new StatementProperties(true, "gpt-5.4-nano"));

  @Test
  void keepsOnlyVerdictsItCanTrust() {
    answer("""
        {"results":[
          {"index":1,"shared":true,"confidence":"high","title":"Gym term fees",
           "category":"activities","childIds":["%s","ffffffffffffffffffffffff"],
           "reason":"A children's gym club"},
          {"index":2,"shared":false,"confidence":null,"title":null,"category":null,
           "childIds":[],"reason":null},
          {"index":2,"shared":true,"confidence":"high","title":"Duplicate","category":"food",
           "childIds":[],"reason":"Second answer for the same row"},
          {"index":9,"shared":true,"confidence":"high","title":"Invented","category":"food",
           "childIds":[],"reason":"No such row"},
          {"index":3,"shared":true,"confidence":"certain","title":"Pharmacy",
           "category":"sweets","childIds":[],"reason":"Could be for Robin"}
        ]}""".formatted(ROBIN));

    final List<StatementClassifier.Verdict> verdicts = classifier.classify(context(), items(4));

    assertThat(verdicts).hasSize(4);
    assertThat(verdicts.get(0)).isEqualTo(new StatementClassifier.Verdict(true, "high",
        "Gym term fees", "activities", List.of(ROBIN), "A children's gym club"));
    assertThat(verdicts.get(1).shared()).isFalse();
    // Unknown confidence and category fall back to the most cautious values.
    assertThat(verdicts.get(2).confidence()).isEqualTo("low");
    assertThat(verdicts.get(2).category()).isEqualTo("other");
    // A row the model skipped is not shared.
    assertThat(verdicts.get(3)).isEqualTo(StatementClassifier.Verdict.notShared());
  }

  @Test
  @SuppressWarnings("unchecked")
  void asksForStrictStructuredOutputAndKeepsContentOutOfTraces() throws Exception {
    answer("{\"results\":[]}");

    classifier.classify(context(), items(1));

    final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    final OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("gpt-5.4-nano");
    assertThat(options.getReasoningEffort()).isEqualTo("none");
    assertThat(options.getResponseFormat().getType())
        .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
    assertThat(options.getResponseFormat().getStrict()).isTrue();
    assertThat(options.getToolCallbacks()).isNullOrEmpty();
    assertThat(prompt.getValue().getInstructions()).allSatisfy(message ->
        assertThat(message.getMetadata()).containsKey("coparent.assistant.suppress-content"));
    assertThat(prompt.getValue().getInstructions().getLast().getText())
        .contains("\"amount\":\"1.05\"").contains("\"index\":1");
    assertThat(prompt.getValue().getInstructions().getFirst().getText())
        .contains("\"OTHER_PARENT\":\"Sam\"").contains("\"name\":\"Robin\"")
        .contains("Kids Gym");

    final Map<String, Object> schema = new ObjectMapper().readValue(options.getResponseFormat()
        .getJsonSchema(), new TypeReference<>() { });
    final Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) schema.get("properties")).get("results")).get("items");
    assertThat((List<String>) item.get("required")).containsExactlyInAnyOrderElementsOf(
        ((Map<String, Object>) item.get("properties")).keySet());
    assertThat(item.get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void refusesMalformedAnswersAndOversizedBatches() {
    answer("not json");
    assertThatThrownBy(() -> classifier.classify(context(), items(1)))
        .isInstanceOf(ResponseStatusException.class);

    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
    assertThatThrownBy(() -> classifier.classify(context(), items(1)))
        .isInstanceOf(ResponseStatusException.class);

    assertThatThrownBy(() -> classifier.classify(context(),
        items(StatementClassifier.MAX_BATCH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmptyBatchMakesNoCall() {
    assertThat(classifier.classify(context(), List.of())).isEmpty();
    verify(chatModel, never()).call(any(Prompt.class));
  }

  private void answer(final String json) {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  private static StatementClassifier.Context context() {
    return new StatementClassifier.Context(LocalDate.of(2026, 10, 10), "Alex", "Sam",
        List.of(new StatementClassifier.Child(ROBIN, "Robin", 8, null)),
        List.of(new StatementClassifier.Decision("Kids Gym", true, "activities")));
  }

  private static List<StatementClassifier.Item> items(final int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(index -> new StatementClassifier.Item(LocalDate.of(2026, 10, index % 28 + 1),
            "SHOP " + index, "", 100L + index * 5))
        .toList();
  }
}
