package com.simonrowe.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Copies prompt and completion content onto the span attribute names Langfuse recognises.
 *
 * <p>Spring AI does not do this itself at any version: {@code log-prompt} / {@code
 * log-completion} only produce SLF4J log lines, and {@code AiObservationAttributes} has no
 * prompt or completion constant. Without this filter, Langfuse shows generations with null
 * input and output, which also makes LLM-as-a-judge evaluators useless.
 *
 * <p>Registered at lowest precedence so it runs after Spring AI's own
 * {@code ToolCallingContentObservationFilter}, whose output it remaps.
 */
public class LangfuseContentObservationFilter implements ObservationFilter {

  private static final Logger LOG =
      LoggerFactory.getLogger(LangfuseContentObservationFilter.class);

  private static final String TOOL_ARGUMENTS_KEY = "spring.ai.tool.call.arguments";
  private static final String TOOL_RESULT_KEY = "spring.ai.tool.call.result";

  private final LangfuseProperties properties;

  public LangfuseContentObservationFilter(final LangfuseProperties properties) {
    this.properties = properties;
  }

  @Override
  public Observation.Context map(final Observation.Context context) {
    if (!properties.isContentCaptureEnabled()) {
      return context;
    }
    try {
      if (context instanceof ChatModelObservationContext chatContext) {
        mapChatContent(chatContext);
      }
      remapToolContent(context);
    } catch (Exception e) {
      // Never let observability break the call it is observing.
      LOG.warn("Failed to attach Langfuse content attributes", e);
    }
    return context;
  }

  private void mapChatContent(final ChatModelObservationContext context) {
    if (context.getRequest() != null && context.getRequest().getInstructions() != null) {
      String prompt = context.getRequest().getInstructions().stream()
          .map(LangfuseContentObservationFilter::renderMessage)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_INPUT, prompt);
    }

    ChatResponse response = context.getResponse();
    if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
      String completion = response.getResults().stream()
          .map(LangfuseContentObservationFilter::renderGeneration)
          .collect(Collectors.joining("\n"));
      put(context, LangfuseAttributes.OBSERVATION_OUTPUT, completion);
    }
  }

  private void remapToolContent(final Observation.Context context) {
    copyKey(context, TOOL_ARGUMENTS_KEY, LangfuseAttributes.OBSERVATION_INPUT);
    copyKey(context, TOOL_RESULT_KEY, LangfuseAttributes.OBSERVATION_OUTPUT);
  }

  private void copyKey(final Observation.Context context, final String from, final String to) {
    context.getHighCardinalityKeyValues().stream()
        .filter(keyValue -> keyValue.getKey().equals(from))
        .map(KeyValue::getValue)
        .findFirst()
        .ifPresent(value -> put(context, to, value));
  }

  private void put(final Observation.Context context, final String key, final String value) {
    String capped = LangfuseAttributes.truncate(value);
    if (capped != null && !capped.isEmpty()) {
      context.addHighCardinalityKeyValue(KeyValue.of(key, capped));
    }
  }

  private static String renderMessage(final Message message) {
    String text = message.getText();
    return message.getMessageType() + ": " + (text == null ? "" : text);
  }

  private static String renderGeneration(final Generation generation) {
    if (generation.getOutput() == null || generation.getOutput().getText() == null) {
      return "";
    }
    return generation.getOutput().getText();
  }
}
