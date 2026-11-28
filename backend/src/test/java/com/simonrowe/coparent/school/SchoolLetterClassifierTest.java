package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class SchoolLetterClassifierTest {

  private final ChatModel chatModel = mock(ChatModel.class);
  private final SchoolLetterClassifier.Letter letter = new SchoolLetterClassifier.Letter(
      "Year 6 residential", "Kingswood consent.pdf", "Please sign and return " + "x".repeat(5000),
      "Dear parents");

  @Test
  void keepsConsentFormAndAsksWithNoToolsStrictSchemaAndCappedText() {
    answer("{\"keep\":true,\"kind\":\"consent\",\"reason\":\"A form to sign.\"}");

    assertThat(classifier(true).classify(letter)).hasValueSatisfying(verdict -> {
      assertThat(verdict.keep()).isTrue();
      assertThat(verdict.kind()).isEqualTo("consent");
      assertThat(verdict.reason()).isEqualTo("A form to sign.");
    });
    final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    final OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("test-model");
    assertThat(options.getReasoningEffort()).isEqualTo("none");
    assertThat(options.getToolCallbacks()).isNullOrEmpty();
    assertThat(options.getResponseFormat().getJsonSchema()).contains("certificate");
    assertThat(prompt.getValue().getContents()).contains("Kingswood consent.pdf")
        .doesNotContain("x".repeat(2500));
    assertThat(prompt.getValue().getInstructions()).allSatisfy(message ->
        assertThat(message.getMetadata()).containsKey("coparent.assistant.suppress-content"));
  }

  @Test
  void menuIsClearNo() {
    answer("{\"keep\":false,\"kind\":\"other\",\"reason\":\"A lunch menu.\"}");
    assertThat(classifier(true).classify(letter)).hasValueSatisfying(verdict ->
        assertThat(verdict.keep()).isFalse());
  }

  @Test
  void anyFailureOrContradictionIsNoAnswer() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("down"));
    assertThat(classifier(true).classify(letter)).isEmpty();

    for (final String json : List.of("not json", "",
        "{\"keep\":true,\"kind\":\"other\",\"reason\":\"\"}",
        "{\"keep\":false,\"kind\":\"consent\",\"reason\":\"\"}",
        "{\"keep\":true,\"kind\":\"invoice\",\"reason\":\"\"}")) {
      answer(json);
      assertThat(classifier(true).classify(letter)).as(json).isEmpty();
    }
  }

  @Test
  void switchedOffItAsksNothing() {
    assertThat(classifier(false).classify(letter)).isEmpty();
    verify(chatModel, never()).call(any(Prompt.class));
  }

  private void answer(final String json) {
    when(chatModel.call(any(Prompt.class))).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  private SchoolLetterClassifier classifier(final boolean enabled) {
    return new SchoolLetterClassifier(chatModel,
        new SchoolDocumentOfferProperties(true, enabled, "test-model", 10, 60, null));
  }
}
