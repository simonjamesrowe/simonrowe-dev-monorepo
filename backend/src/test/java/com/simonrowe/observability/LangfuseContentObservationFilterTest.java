package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

class LangfuseContentObservationFilterTest {

  private static LangfuseProperties enabledProperties() {
    LangfuseProperties properties = new LangfuseProperties();
    properties.setContentCaptureEnabled(true);
    return properties;
  }

  private static String valueOf(final Observation.Context context, final String key) {
    return context.getHighCardinalityKeyValues().stream()
        .filter(keyValue -> keyValue.getKey().equals(key))
        .map(KeyValue::getValue)
        .findFirst()
        .orElse(null);
  }

  private static ChatModelObservationContext chatContext(final String userText,
      final String assistantText) {
    ChatModelObservationContext context = ChatModelObservationContext.builder()
        .prompt(new Prompt(new UserMessage(userText)))
        .provider("openai")
        .build();
    if (assistantText != null) {
      context.setResponse(new ChatResponse(
          List.of(new Generation(new AssistantMessage(assistantText)))));
    }
    return context;
  }

  @Test
  void writesPromptAndCompletionOntoLangfuseAttributes() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(chatContext("What is Kafka?", "A log."));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).contains("What is Kafka?");
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).contains("A log.");
  }

  @Test
  void omitsOutputWhenResponseIsAbsent() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(chatContext("Question with no answer", null));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNotNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  @Test
  void truncatesContentThatExceedsTheCap() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    String huge = "y".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + 500);

    Observation.Context result = filter.map(chatContext(huge, "short"));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).endsWith("…[truncated]");
  }

  @Test
  void doesNothingWhenContentCaptureIsDisabled() {
    LangfuseProperties disabled = new LangfuseProperties();
    disabled.setContentCaptureEnabled(false);
    LangfuseContentObservationFilter filter = new LangfuseContentObservationFilter(disabled);

    Observation.Context result = filter.map(chatContext("What is Kafka?", "A log."));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  /**
   * Built from the real Spring AI context rather than by injecting {@code spring.ai.tool.call.*}
   * key values by hand: the previous hand-injected form passed even though the production code
   * never saw those keys, because Spring AI's own filter is not guaranteed to run first.
   */
  private static ToolCallingObservationContext toolContext(final String arguments,
      final String result) {
    return ToolCallingObservationContext.builder()
        .toolDefinition(ToolDefinition.builder()
            .name("searchBlogs")
            .description("Search blog posts")
            .inputSchema("{}")
            .build())
        .toolCallArguments(arguments)
        .toolCallResult(result)
        .build();
  }

  @Test
  void writesToolCallArgumentsAndResultOntoLangfuseAttributes() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(
        toolContext("{\"keyword\":\"kafka\"}", "[{\"title\":\"A blog\"}]"));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT))
        .isEqualTo("{\"keyword\":\"kafka\"}");
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT))
        .isEqualTo("[{\"title\":\"A blog\"}]");
  }

  @Test
  void omitsToolResultWhileTheToolCallIsStillInFlight() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());

    Observation.Context result = filter.map(toolContext("{\"keyword\":\"kafka\"}", null));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNotNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  @Test
  void truncatesToolResultThatExceedsTheCap() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    String huge = "z".repeat(LangfuseAttributes.MAX_ATTRIBUTE_CHARS + 500);

    Observation.Context result = filter.map(toolContext("{}", huge));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).endsWith("…[truncated]");
  }

  @Test
  void writesNoToolContentWhenContentCaptureIsDisabled() {
    LangfuseProperties disabled = new LangfuseProperties();
    disabled.setContentCaptureEnabled(false);
    LangfuseContentObservationFilter filter = new LangfuseContentObservationFilter(disabled);

    Observation.Context result = filter.map(
        toolContext("{\"keyword\":\"kafka\"}", "[{\"title\":\"A blog\"}]"));

    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
    assertThat(valueOf(result, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
  }

  @Test
  void leavesUnrelatedContextsUntouched() {
    LangfuseContentObservationFilter filter =
        new LangfuseContentObservationFilter(enabledProperties());
    Observation.Context context = new Observation.Context();

    Observation.Context result = filter.map(context);

    assertThat(result.getHighCardinalityKeyValues()).isEmpty();
  }
}
